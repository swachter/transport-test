package eu.swdev.ttest.server;

import eu.swdev.ttest.Util;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.EndpointManager;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.server.resources.CoapExchange;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Date;

public class Server extends CoapServer {

  public static void main(String[] args) throws Exception {

    Server server = new Server();
    server.start();

  }

  private static final int COAP_PORT = NetworkConfig.getStandard().getInt(NetworkConfig.Keys.COAP_PORT);

  public static class Posted {
    public final Date date;
    public final String value;

    public Posted(Date date, String value) {
      this.date = date;
      this.value = value;
    }
  }
  //
  //
  //

  private CircularFifoQueue<Posted> queue = new CircularFifoQueue<Posted>(10);
  private int count = 0;

  public Server() throws Exception {
    addResources();
    addEndpoints();
  }

  private void addResources() {
    add(new TestResource());
  }

  /**
   * Add individual endpoints listening on default CoAP port on all IPv4 addresses of all network interfaces.
   */
  private void addEndpoints() throws Exception {
    for (InetAddress addr : EndpointManager.getEndpointManager().getNetworkInterfaces()) {
      // only binds to IPv4 addresses and localhost
      if (addr instanceof Inet4Address || addr.isLoopbackAddress()) {
        InetSocketAddress bindToAddress = new InetSocketAddress(addr, COAP_PORT);
        addEndpoint(new CoapEndpoint(bindToAddress) {
          @Override
          public URI getUri() {
            try {
              return super.getUri();
            } catch (Exception e) {
              return null;
            }
          }
        });
      }
      addEndpoint(new CoapEndpoint(Util.createDtlsConnector("server", 5684), NetworkConfig.getStandard()));
    }
  }

  public String getPostedInfo() {
    StringBuilder sb = new StringBuilder();
    synchronized (queue) {
      sb.append("count: ").append(count).append('\n').append("last posts\n");
      for (Posted posted: queue) {
        sb.append(posted.date.getTime()).append(": ").append(posted.value).append('\n');
      }
    }
    return sb.toString();
  }

  public class TestResource extends CoapResource {

    public TestResource() {
      super("test");
    }

    @Override
    public void handleGET(CoapExchange exchange) {
      exchange.respond(getPostedInfo());
    }

    @Override
    public void handlePOST(CoapExchange exchange) {
      synchronized (queue) {
        count++;
        queue.add(new Posted(new Date(), exchange.getRequestText()));
      }
      exchange.respond(CoAP.ResponseCode.CREATED);
    }

  }


}
