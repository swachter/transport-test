package eu.swdev.ttest.client;

import eu.swdev.ttest.DtlsSecurity;
import eu.swdev.ttest.Util;
import org.HdrHistogram.Histogram;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.scandium.DTLSConnector;

import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Client {

  public static final boolean remoteHost = System.getProperty("remote") != null;
  public static final String host = System.getProperty("host", remoteHost ? "swachter.p7.de" : "localhost");

  private enum PostResult {
    Null, Success, Failure, Exception;
  }

  private static NetworkConfig networkConfig = NetworkConfig.createStandardWithoutFile();

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

  private static DtlsSecurity dtlsSecurity = DtlsSecurity.CLIENT_PSK;

  private static CoapClient createDtlsCoapClient() {
    DTLSConnector connector = Util.createDtlsConnector(new InetSocketAddress(0), dtlsSecurity);
    return new DtlsCoapClient(connector, "coaps", host, 5684, "dtls")
        .useNONs()
        .setEndpoint(new CoapEndpoint(connector, networkConfig))
        .setTimeout(10000);
  }

  private static void printResponse(String headline, CoapResponse response) {
    System.out.println(headline + " - RTT: " + response.advanced().getRTT() + "ms");
    //System.out.println("code   : " + response.getCode());
    //System.out.println("options: " + response.getOptions());
    //System.out.println("text   : " + response.getResponseText());
  }

  private enum Protocol {

    Udp(
        'u',
        () -> new CoapClient("coap", host, 5683, "udp")
            .useNONs()
    ),
    Dtls(
        'U',
        () -> createDtlsCoapClient()
    ),
    Tcp(
        't',
        () -> new CoapClient("coap+tcp", host, 5685, "tcp")
            .useNONs()
            .setEndpoint(new CoapEndpoint(Util.createTcpClientConnector(), networkConfig))
    ),
    Tls(
        'T',
        () -> new CoapClient("coaps+tcp", host, 5686, "tls")
            .useNONs()
            .setEndpoint(new CoapEndpoint(Util.createTlsClientConnector(), networkConfig))
    );

    private final char key;
    private final Supplier<CoapClient> coapClientSupplier;
    private CoapClient coapClient;


    Protocol(char key, Supplier<CoapClient> coapClientSupplier) {
      this.key = key;
      this.coapClientSupplier = coapClientSupplier;
      coapClient = coapClientSupplier.get();
    }

    public PostResult post(int experiment, int request) {
      try {
        CoapResponse response = coapClient.post("" + experiment + ":" + request, 0);
        if (response != null) {
          printResponse("post response (" + this + ")", response);
          if (response.getCode().codeClass == CoAP.CodeClass.SUCCESS_RESPONSE.value) {
            return PostResult.Success;
          } else {
            return PostResult.Failure;
          }
        } else {
          System.out.println("no post response received (" + this + ")");
          return PostResult.Null;
        }
      } catch (Exception e) {
        e.printStackTrace();
        return PostResult.Exception;
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

    public void reset() throws Exception {
      // UDP-CoapClient uses default endpoint
      // -> do not reset endpoint (UDP is sessionless anyway)
      if (coapClient.getEndpoint() == null) {
        System.out.println("can not reset protocol: " + this);
        return;
      }
      coapClient.shutdown();
      coapClient.getEndpoint().destroy();
      coapClient = coapClientSupplier.get();
    }
  }

  static class Stats {
    int requests = 0;
    Map<PostResult, Histogram> durations = new HashMap<>();

    Histogram getDurations(PostResult postResult) {
      Histogram h = durations.get(postResult);
      if (h == null) {
        h = new Histogram(5);
        durations.put(postResult, h);
      }
      return h;
    }
  }

  static int experimentCounter = 0;

  static class Experiment {
    final int number = experimentCounter++;
    final Map<Protocol, Stats> stats = new HashMap<>();

    Stats getStats(Protocol protocol) {
      Stats s = stats.get(protocol);
      if (s == null) {
        s = new Stats();
        stats.put(protocol, s);
      }
      return s;
    }
  }

  static PushbackInputStream keyboardInput = new PushbackInputStream(System.in);

  Experiment experiment = new Experiment();

  boolean postNotWarmUpRepetitions = true;
  int postRepetitions = 1;
  int warmUpRepetitions = 0;

  void warmUp(Protocol protocol, int repetitions) throws Exception {
    for (int i = 0; i < repetitions; i++) {
      protocol.post(experiment.number, -1);
    }
  }

  void post(Protocol protocol, int repetitions) throws Exception {
    Stats stats = experiment.getStats(protocol);
    for (int i = 0; i < repetitions; i++) {
      long start = System.nanoTime();
      PostResult postResult = protocol.post(experiment.number, stats.requests++);
      long duration = (System.nanoTime() - start + 500000) / 1000000;
      stats.getDurations(postResult).recordValue(duration);
    }
  }

  void warmUpAndPost(Protocol protocol) throws Exception {
    warmUp(protocol, warmUpRepetitions);
    post(protocol, postRepetitions);
  }

  Set<Protocol> protocols = new LinkedHashSet<Protocol>() {{
    add(Protocol.Dtls);
    add(Protocol.Tls);
  }};

  void showStats(Protocol protocol) {
    System.out.println("==> Stats (" + protocol + ")");
    Stats stats = experiment.getStats(protocol);
    System.out.println("posted requests: " + stats.requests);
    for (Map.Entry<PostResult, Histogram> me : stats.durations.entrySet()) {
      PostResult postResult = me.getKey();
      Histogram histogram = me.getValue();
      System.out.println("" + postResult + " - count: " + histogram.getTotalCount() +
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

  void repl() throws Exception {
    int r;

    while ((r = keyboardInput.read()) != -1) {

      switch (r) {
        case 'u':
          warmUpAndPost(Protocol.Udp);
          break;
        case 'U':
          warmUpAndPost(Protocol.Dtls);
          break;
        case 't':
          warmUpAndPost(Protocol.Tcp);
          break;
        case 'T':
          warmUpAndPost(Protocol.Tls);
          break;

        // post for all selected protocols
        case 'p': {
          System.out.println("begin warmup");
          for (int i = 0; i < warmUpRepetitions; i++) {
            for (Protocol p : protocols) {
              warmUp(p, 1);
            }
          }
          System.out.println("end warmup");
          for (int i = 0; i < postRepetitions; i++) {
            for (Protocol p : protocols) {
              post(p, 1);
            }
          }
          System.out.println("finished");
          break;
        }

        case 'P': {
          System.out.println("begin warmup");
          for (int i = 0; i < warmUpRepetitions; i++) {
            for (Protocol p : protocols) {
              p.reset();
              warmUp(p, 1);
            }
          }
          System.out.println("end warmup");
          for (int i = 0; i < postRepetitions; i++) {
            for (Protocol p : protocols) {
              p.reset();
              post(p, 1);
            }
          }
          System.out.println("finished");
          break;
        }

        case 'r': {
          for (Protocol p : protocols) {
            p.reset();
          }
          break;
        }

        case 'h': {
          if ((r = keyboardInput.read()) != -1) {
            switch (r) {
              case 'p':
                dtlsSecurity = DtlsSecurity.CLIENT_PSK;
                break;
              case 'r':
                dtlsSecurity = DtlsSecurity.CLIENT_RPK;
                break;
              case 'x':
                dtlsSecurity = DtlsSecurity.CLIENT_X509;
                break;
              default:
                System.out.println("unknown handshake '" + (char)r + "'; use p (for psk), r (for rpk), or x for (x.509)");
                keyboardInput.unread(r);
            }
          }
          break;
        }
        case 'e':
          experiment = new Experiment();
          System.out.println("start new experiment #" + experiment.number);
          break;

        case 's':
          for (Protocol p : protocols) {
            showStats(p);
          }
          break;

        case 'S':
          for (Protocol p : protocols) {
            showStats(p);
            Integer countOnServer = p.get(experiment.number);
            if (countOnServer == null) {
              System.out.println("countOnServer keyboardInput unknown");
            } else {
              System.out.println("countOnSever: " + countOnServer + "; ratio: " + countOnServer.doubleValue() / experiment.getStats(p).requests);
            }
          }
          break;

        case 'n':
          postRepetitions = 0;
          postNotWarmUpRepetitions = true;
          break;

        case 'w':
          warmUpRepetitions = 0;
          postNotWarmUpRepetitions = false;
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
          if (postNotWarmUpRepetitions) {
            postRepetitions = postRepetitions * 10 + (r - '0');
          } else {
            warmUpRepetitions = warmUpRepetitions * 10 + (r - '0');
          }
          break;

        // remove a protocol
        case '-': {
          int i = keyboardInput.read();
          for (Protocol p : protocols) {
            if (p.key == (char) i) {
              protocols.remove(p);
              break;
            }
          }
        }
        break;

        // add a protocol
        case '+': {
          int i = keyboardInput.read();
          for (Protocol p : Protocol.values()) {
            if (p.key == (char) i) {
              protocols.add(p);
              break;
            }
          }
        }
        break;

        case '?':
          usage();
          break;

        case 'i':
          System.out.println("experiment: " + experiment.number + "; protocols: " + protocols + "; handshake: " + dtlsSecurity.handshake + "; postRepetitions: " + postRepetitions + "; warmUpRepetitions: " + warmUpRepetitions);
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
    System.out.println("u: post via UDP");
    System.out.println("U: post via DTLS");
    System.out.println("t: post via TCP");
    System.out.println("T: post via TLS");
    System.out.println("p: post via all selected protocols");
    System.out.println("P: reset and post via all selected protocols");
    System.out.println("r: reset all selected protocols");
    System.out.println("h<p|r|x>: set handshake (PSK, RPK, or X.509)");
    System.out.println("");
    System.out.println("e: start a new experiment");
    System.out.println("n<digits*>: set number of post repetitions");
    System.out.println("+<u|U|t|T>: add a protocol to selection");
    System.out.println("-<u|U|t|T>: remove a protocol from selection");
    System.out.println("");
    System.out.println("s: show current post statistics");
    System.out.println("S: show current post statistics and server counts");
    System.out.println("i: show current parameters");
    System.out.println("?: show this help message");
    System.out.println("");
    System.out.println("q: quit");
  }
}
