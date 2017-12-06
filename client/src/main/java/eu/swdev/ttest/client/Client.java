package eu.swdev.ttest.client;

import eu.swdev.ttest.DtlsSecurity;
import eu.swdev.ttest.Util;
import org.HdrHistogram.Histogram;
import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.scandium.DTLSConnector;

import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static eu.swdev.ttest.Util.networkConfig;

public class Client {

  public static final boolean remoteHost = System.getProperty("remote") != null;
  public static final String host = System.getProperty("host", remoteHost ? "swachter.p7.de" : "localhost");

  private enum Result {
    Null, Success, Failure, Exception;
  }

  /**
   * Subclass CoapClient in order to trigger forceResumeAllSession after 30 secs of inactivity.
   */
  private static class DtlsCoapClient extends CoapClient {

    private final AtomicLong lastAccess = new AtomicLong();
    private final DTLSConnector connector;

    public DtlsCoapClient(DTLSConnector connector, String scheme, String host, int port, String... path) {
      super(scheme, host, port, path);
      this.connector = connector;
    }

    private void checkLastAccess() {
      if (lastAccess.get() != 0 && lastAccess.get() < System.currentTimeMillis() - 30000) {
        System.out.println("####### -> force resume");
        connector.forceResumeAllSessions();
        System.out.println("####### <- force resume");
      }
      lastAccess.set(System.currentTimeMillis());
    }

    @Override
    public CoapResponse get() {
      checkLastAccess();
      return super.get();
    }

    @Override
    public CoapResponse post(String payload, int format) {
      checkLastAccess();
      return super.post(payload, format);
    }
  }

  //
  //
  //

  private static CoapClient createDtlsCoapClient(DtlsSecurity dtlsSecurity, String path) {
    DTLSConnector connector = Util.createDtlsConnector(new InetSocketAddress(0), dtlsSecurity);
    return new DtlsCoapClient(connector, "coaps", host, 5684, path)
        .useNONs()
        .setEndpoint(new CoapEndpoint(connector, networkConfig));
  }

  private static void printResponse(String headline, CoapResponse response) {
    System.out.println(headline + " - RTT: " + response.advanced().getRTT() + "ms");
    //System.out.println("code   : " + response.getCode());
    //System.out.println("options: " + response.getOptions());
    //System.out.println("text   : " + response.getResponseText());
  }

  private static boolean postMorePayload = false;
  private static String morePayload = RandomStringUtils.randomAlphabetic(500);


  private enum Protocol {

    Udp("udp", path -> new CoapClient("coap", host, 5683, path)
        .useNONs()
    ),
    DtlsPsk("dtls+psk", path -> createDtlsCoapClient(DtlsSecurity.CLIENT_PSK, path)
    ),
    DtlsRpk("dtls+rpk", path -> createDtlsCoapClient(DtlsSecurity.CLIENT_RPK, path)
    ),
    DtlsX509("dtls+x509", path -> createDtlsCoapClient(DtlsSecurity.CLIENT_X509, path)
    ),
    Tcp("tcp", path -> new CoapClient("coap+tcp", host, 5685, path)
        .setEndpoint(new CoapEndpoint(Util.createTcpClientConnector(), networkConfig))
    ),
    Tls("tls", path -> new CoapClient("coaps+tcp", host, 5686, path)
        .setEndpoint(new CoapEndpoint(Util.createTlsClientConnector(), networkConfig))
    );

    private final String path;
    private final Function<String, CoapClient> coapClientSupplier;
    private CoapClient coapClient;
    private CoapClient longPayloadClient;

    Protocol(String path, Function<String, CoapClient> coapClientSupplier) {
      this.path = path;
      this.coapClientSupplier = coapClientSupplier;
      reset();
    }

    public Result post(int experiment, int request) {
      try {
        String payload;
        if (postMorePayload) {
          payload = "" + experiment + ":" + request + "\n" + morePayload;
        } else {
          payload = "" + experiment + ":" + request;
        }
        CoapResponse response = coapClient.post(payload, 0);
        if (response != null) {
          printResponse("post response (" + this + ")", response);
          if (response.getCode().codeClass == CoAP.CodeClass.SUCCESS_RESPONSE.value) {
            return Result.Success;
          } else {
            return Result.Failure;
          }
        } else {
          System.out.println("no post response received (" + this + ")");
          return Result.Null;
        }
      } catch (Exception e) {
        e.printStackTrace();
        return Result.Exception;
      }
    }

    public Integer get(int experiment) {
      CoapResponse response = coapClient.get();
      if (response != null) {
        printResponse("get response (" + this + ")", response);
        String text = response.getResponseText();
        Pattern pattern = Pattern.compile("^" + experiment + ":(\\d+)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
          return Integer.parseInt(matcher.group(1));
        } else {
          System.out.println("no counter found for experiment #" + experiment + " (" + this + ")");
          return 0;
        }
      } else {
        System.out.println("no get response received (" + this + ")");
        return null;
      }
    }

    public Result getLongPayload() {
      try {
        CoapResponse response = longPayloadClient.get();
        if (response != null) {
          printResponse("long payload response (" + this + ")", response);
          if (response.getResponseText() != null) {
            System.out.println("response length: " + response.getResponseText().length());
          } else {
            System.out.println("no response text found");
          }
          if (response.getCode().codeClass == CoAP.CodeClass.SUCCESS_RESPONSE.value) {
            return Result.Success;
          } else {
            return Result.Failure;
          }
        } else {
          System.out.println("no long payload response received (" + this + ")");
          return Result.Null;
        }
      } catch (Exception e) {
        e.printStackTrace();
        return Result.Exception;
      }
    }

    private void destroyClient(CoapClient client) {
      if (client != null) {
        // UDP-CoapClient uses default endpoint
        // -> do not reset endpoint (UDP is sessionless anyway)
        if (client.getEndpoint() == null) {
          System.out.println("can not reset protocol: " + this);
          return;
        }
        client.shutdown();
        client.getEndpoint().destroy();
      }
    }

    public final void reset() {
      destroyClient(coapClient);
      destroyClient(longPayloadClient);
      coapClient = coapClientSupplier.apply(path).setTimeout(10000);
      longPayloadClient = coapClientSupplier.apply(path + "morePayload").setTimeout(180000);
    }
  }

  //
  //
  //

  static class Stats {
    int requests = 0;
    Map<Result, Histogram> durations = new HashMap<>();

    Histogram getDurations(Result result) {
      Histogram h = durations.get(result);
      if (h == null) {
        h = new Histogram(5);
        durations.put(result, h);
      }
      return h;
    }
  }

  static int experimentCounter = 0;

  static class Experiment {
    final int number = experimentCounter++;
    final Map<Protocol, Stats> stats = new HashMap<>();
    final Map<Protocol, Stats> longPayloadStats = new HashMap<>();

    Stats getPostStats(Protocol protocol) {
      Stats s = stats.get(protocol);
      if (s == null) {
        s = new Stats();
        stats.put(protocol, s);
      }
      return s;
    }

    Stats getLongPayloadStats(Protocol protocol) {
      Stats s = longPayloadStats.get(protocol);
      if (s == null) {
        s = new Stats();
        longPayloadStats.put(protocol, s);
      }
      return s;
    }
  }

  static PushbackInputStream keyboardInput = new PushbackInputStream(System.in);

  Experiment experiment = new Experiment();

  boolean requestNotWarmUpRepetitions = true;
  int requestRepetitions = 1;
  int warmUpRepetitions = 0;

  void post(Protocol protocol, int repetitions, boolean isWarmUp) {
    if (isWarmUp) {
      for (int i = 0; i < repetitions; i++) {
        protocol.post(experiment.number, -1);
      }
    } else {
      Stats stats = experiment.getPostStats(protocol);
      for (int i = 0; i < repetitions; i++) {
        long start = System.nanoTime();
        Result result = protocol.post(experiment.number, stats.requests++);
        long duration = (System.nanoTime() - start + 500000) / 1000000;
        stats.getDurations(result).recordValue(duration);
      }
    }
  }

  void getLongPayload(Protocol protocol, int repetitions, boolean isWarmUp) {
    if (isWarmUp) {
      for (int i = 0; i < repetitions; i++) {
        protocol.getLongPayload();
      }
    } else {
      Stats stats = experiment.getLongPayloadStats(protocol);
      for (int i = 0; i < repetitions; i++) {
        stats.requests++;
        long start = System.nanoTime();
        Result result = protocol.getLongPayload();
        long duration = (System.nanoTime() - start + 500000) / 1000000;
        System.out.println("duration: " + duration);
        stats.getDurations(result).recordValue(duration);
      }
    }
  }

  Set<Protocol> protocols = new LinkedHashSet<Protocol>() {{
    add(Protocol.DtlsPsk);
    add(Protocol.Tls);
  }};

  void showStats(Stats stats) {
    System.out.println("posted requests: " + stats.requests);
    for (Map.Entry<Result, Histogram> me : stats.durations.entrySet()) {
      Result result = me.getKey();
      Histogram histogram = me.getValue();
      System.out.println("" + result + " - count: " + histogram.getTotalCount() +
          "; min: " + histogram.getMinValue() +
          "; max: " + histogram.getMaxValue() +
          "; mean: " + histogram.getMean() + "; " +
          "; stdDeviation: " + histogram.getStdDeviation() +
          "; p25: " + histogram.getValueAtPercentile(25) +
          "; p50: " + histogram.getValueAtPercentile(50) +
          "; p75: " + histogram.getValueAtPercentile(75) +
          "; p90: " + histogram.getValueAtPercentile(90) +
          "; p95: " + histogram.getValueAtPercentile(95) +
          "; p98: " + histogram.getValueAtPercentile(98));
    }
  }

  void showProtocolStats(Protocol protocol) {
    System.out.println("==> Stats (" + protocol + ")");
    showStats(experiment.getPostStats(protocol));
    showStats(experiment.getLongPayloadStats(protocol));
  }

  Protocol inputProtocol() throws Exception {

    int r;
    if ((r = keyboardInput.read()) != -1) {
      switch (r) {

        case 't':
          return Protocol.Tcp;
        case 'T':
          return Protocol.Tls;
        case 'u':
          return Protocol.Udp;
        case 'U':
          return Protocol.DtlsPsk;
        case 'd':
          if ((r = keyboardInput.read()) != -1) {
            switch (r) {
              case 'p':
                return Protocol.DtlsPsk;
              case 'r':
                return Protocol.DtlsRpk;
              case 'x':
                return Protocol.DtlsX509;
              default:
                System.out.println("unknown handshake '" + (char) r + "'; use p (for psk), r (for rpk), or x for (x.509)");
                return null;
            }
          } else {
            return null;
          }
        default:
          System.out.println("unknown protocol '" + (char) r + "'; use t (for TCP), T (for tls), u (for UDP), U (for DTLS with PSK), d (for DTLS with handshake psk, rpk, or X.509))");
          return null;
      }
    } else {
      return null;
    }

  }

  void doWithWarmUp(BiConsumer<Protocol, Boolean> func) {
    if (warmUpRepetitions > 0) System.out.println("begin warmup");
    for (int i = 0; i < warmUpRepetitions; i++) {
      for (Protocol p : protocols) {
        func.accept(p, true);
      }
    }
    if (warmUpRepetitions > 0) {
      System.out.println("end warmup");
    } else {
      System.out.println("start");
    }
    for (int i = 0; i < requestRepetitions; i++) {
      for (Protocol p : protocols) {
        func.accept(p, false);
      }
    }
    System.out.println("finished");

  }

  void repl() throws Exception {
    int r;

    while ((r = keyboardInput.read()) != -1) {

      switch (r) {

        // post for all selected protocols
        case 'p':
          doWithWarmUp((protocol, warmUp) -> post(protocol, 1, warmUp));
          break;

        case 'P':
          doWithWarmUp((protocol, warmUp) -> {
            protocol.reset();
            post(protocol, 1, warmUp);
          });
          break;

        case 'g':
          doWithWarmUp((protocol, warmUp) -> getLongPayload(protocol, 1, warmUp));
          break;

        case 'G':
          doWithWarmUp((protocol, warmUp) -> {
            protocol.reset();
            getLongPayload(protocol, 1, warmUp);
          });
          break;

        case 'r': {
          for (Protocol p : protocols) {
            p.reset();
          }
          break;
        }

        case 'e':
          experiment = new Experiment();
          System.out.println("start new experiment #" + experiment.number);
          break;

        case 's':
          for (Protocol p : protocols) {
            showProtocolStats(p);
          }
          break;

        case 'S':
          for (Protocol p : protocols) {
            showProtocolStats(p);
            Integer countOnServer = p.get(experiment.number);
            if (countOnServer == null) {
              System.out.println("countOnServer unknown");
            } else {
              int requests = experiment.getPostStats(p).requests;
              if (requests != 0) {
                System.out.println("countOnSever: " + countOnServer + "; ratio: " + countOnServer.doubleValue() / experiment.getPostStats(p).requests);
              } else {
                System.out.println("countOnSever: " + countOnServer + "; sentRequests: 0");
              }
            }
          }
          break;

        case 'n':
          requestRepetitions = 0;
          requestNotWarmUpRepetitions = true;
          break;

        case 'w':
          warmUpRepetitions = 0;
          requestNotWarmUpRepetitions = false;
          break;

        case 'l':
          System.out.println("post small payload");
          postMorePayload = false;
          break;

        case 'L':
          System.out.println("post more payload");
          postMorePayload = true;
          break;

        case '0':
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
        case '8':
        case '9':
          if (requestNotWarmUpRepetitions) {
            requestRepetitions = requestRepetitions * 10 + (r - '0');
          } else {
            warmUpRepetitions = warmUpRepetitions * 10 + (r - '0');
          }
          break;

        // remove a protocol
        case '-': {
          Protocol p = inputProtocol();
          if (p != null) {
            if (protocols.remove(p)) {
              System.out.println("removed protocol: " + p);
            } else {
              System.out.println("protocol was not included: " + p);
            }
          }
        }
        break;

        // add a protocol
        case '+': {
          Protocol p = inputProtocol();
          if (p != null) {
            if (protocols.add(p)) {
              System.out.println("added protocol: " + p);
            } else {
              System.out.println("protocol was already added: " + p);
            }
          }
        }
        break;

        case '#':
          protocols.clear();
          break;

        case '?':
          usage();
          break;

        case 'i':
          System.out.println("experiment: " + experiment.number + "; protocols: " + protocols + "; requestRepetitions: " + requestRepetitions + "; warmUpRepetitions: " + warmUpRepetitions + "; postMorePayload: " + postMorePayload);
          break;

        case 'q':
          System.exit(0);
          break;
        default:
      }
    }

  }

  public static void main(String[] args) throws Exception {

    usage();
    new Client().repl();

  }

  private static void usage() {
    System.out.println("p: post via all selected protocols");
    System.out.println("P: reset and post via all selected protocols");
    System.out.println("g: get a large payload via all selected protocols");
    System.out.println("G: reset and get a large payload via all selected protocols");
    System.out.println("r: reset all selected protocols");
    System.out.println("");
    System.out.println("e: start a new experiment");
    System.out.println("n<digits*>: set number of request repetitions");
    System.out.println("+<protocol>: add a protocol to selection");
    System.out.println("-<protocol>: remove a protocol from selection");
    System.out.println("#: clear protocol selection");
    System.out.println("l: post small payload");
    System.out.println("L: post more payload");
    System.out.println("");
    System.out.println("s: show current post statistics");
    System.out.println("S: show current post statistics and server counts");
    System.out.println("i: show current parameters");
    System.out.println("?: show this help message");
    System.out.println("");
    System.out.println("q: quit");
  }
}

/*


 */