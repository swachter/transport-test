package eu.swdev.ttest.server;

import eu.swdev.ttest.Util;
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

public class Server extends CoapServer {

  public static void main(String[] args) throws Exception {

    Server server = new Server();
    server.start();

  }

  private static final int COAP_PORT = NetworkConfig.getStandard().getInt(NetworkConfig.Keys.COAP_PORT);

  //
  //
  //

  public Server() throws Exception {
    addResources();
    addEndpoints();
  }

  private void addResources() {
    add(new TestResource("udp"));
    add(new TestResource("dtls"));
    add(new TestResource("tcp"));
    add(new TestResource("tls"));
  }

  /**
   * Add individual endpoints listening on default CoAP port on all IPv4 addresses of all network interfaces.
   */
  private void addEndpoints() throws Exception {
    NetworkConfig networkConfig = NetworkConfig.createStandardWithoutFile();
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
      addEndpoint(new CoapEndpoint(Util.createDtlsConnector("server", dtlsBindAddress), networkConfig));

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
      synchronized (sets) {
        String text = exchange.getRequestText();
        int idx = text.indexOf(':');
        int experiment = Integer.parseInt(text.substring(0, idx));
        int request = Integer.parseInt(text.substring(idx + 1));
        Set<Integer> set = sets.get(experiment);
        if (set == null) {
          set = new HashSet<>();
          sets.put(experiment, set);
        }
        set.add(request);
      }
      exchange.respond(CoAP.ResponseCode.CREATED);
    }

  }

}
