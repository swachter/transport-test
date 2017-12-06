package eu.swdev.ttest.server;

import eu.swdev.ttest.DtlsSecurity;
import eu.swdev.ttest.Util;
import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.EndpointManager;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.elements.tcp.TcpServerConnector;

import java.net.*;
import java.util.*;

import static eu.swdev.ttest.Util.networkConfig;

public class Server extends CoapServer {

  public static void main(String[] args) throws Exception {

    Server server = new Server();
    server.start();

  }

  private static final int COAP_PORT = networkConfig.getInt(NetworkConfig.Keys.COAP_PORT);

  private static String longPayload = RandomStringUtils.randomAlphabetic(100000);

  //
  //
  //

  public Server() throws Exception {
    addResources();
    addEndpoints();
  }

  private void addResources() {
    add(new TestResource("udp"));
    add(new TestResource("dtls+psk"));
    add(new TestResource("dtls+rpk"));
    add(new TestResource("dtls+x509"));
    add(new TestResource("tcp"));
    add(new TestResource("tls"));
    add(new LongPayloadResource("udplongPayload"));
    add(new LongPayloadResource("dtls+psklongPayload"));
    add(new LongPayloadResource("dtls+rpklongPayload"));
    add(new LongPayloadResource("dtls+x509longPayload"));
    add(new LongPayloadResource("tcplongPayload"));
    add(new LongPayloadResource("tlslongPayload"));
  }

  /**
   * Add individual endpoints listening on default CoAP port on all IPv4 addresses of all network interfaces.
   */
  private void addEndpoints() throws Exception {
    for (InetAddress addr : EndpointManager.getEndpointManager().getNetworkInterfaces()) {
      // only binds to IPv4 addresses and localhost
      if (addr instanceof Inet4Address || addr.isLoopbackAddress()) {
        InetSocketAddress udpBindAddress = new InetSocketAddress(addr, COAP_PORT);
        addEndpoint(new CoapEndpoint(udpBindAddress));
        if (!(addr instanceof Inet6Address)) {
          InetSocketAddress tcpBindAddress = new InetSocketAddress(addr, 5685);
          addEndpoint(new CoapEndpoint(new TcpServerConnector(tcpBindAddress, 2, 10000), networkConfig));
        }
      }
      InetSocketAddress dtlsBindAddress = new InetSocketAddress(addr, 5684);
      addEndpoint(new CoapEndpoint(Util.createDtlsConnector(dtlsBindAddress, DtlsSecurity.SERVER), networkConfig));

    }
    addEndpoint(new CoapEndpoint(Util.createTlsServerConnector(5686), networkConfig));
  }

  public String getPostedInfo() {
    StringBuilder sb = new StringBuilder();
    for (Resource r: getRoot().getChildren()) {
      if (r instanceof TestResource) {
        sb.append(r.getName()).append("\n");
        sb.append(((TestResource)r).getInfo());
      }
    }
    return sb.toString();
  }

  public class TestResource extends CoapResource {

    public TestResource(String name) {
      super(name);
    }

    private Map<Integer, Set<Integer>> sets = new HashMap<>();

    private String getInfo() {
      StringBuilder sb = new StringBuilder();
      synchronized (sets) {
        for (Map.Entry<Integer, Set<Integer>> me: sets.entrySet()) {
          sb.append(me.getKey()).append(':').append(me.getValue().size()).append('\n');
        }
      }
      return sb.toString();
    }
    @Override
    public void handleGET(CoapExchange exchange) {
      exchange.respond(getInfo());
    }

    @Override
    public void handlePOST(CoapExchange exchange) {
      String text = exchange.getRequestText();
      synchronized (sets) {
        int idx = text.indexOf(':');
        int experiment = Integer.parseInt(text.substring(0, idx));
        int idx2 = idx + 1;
        while (idx2 < text.length() && (Character.isDigit(text.charAt(idx2)) || text.charAt(idx2) == '-')) idx2++;
        int request = Integer.parseInt(text.substring(idx + 1, idx2));
        if (request >= 0) {
          // it is not a warm up request
          Set<Integer> set = sets.get(experiment);
          if (set == null) {
            set = new HashSet<>();
            sets.put(experiment, set);
          }
          set.add(request);
        }
      }
      // echo the request text
      exchange.respond(CoAP.ResponseCode.CREATED, text);
    }

  }

  public class LongPayloadResource extends CoapResource {

    public LongPayloadResource(String name) {
      super(name);
    }

    @Override
    public void handleGET(CoapExchange exchange) {
      exchange.respond(longPayload);
    }

  }

}
