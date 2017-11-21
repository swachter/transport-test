package eu.swdev.ttest.client;

import eu.swdev.ttest.Util;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;

import java.io.Console;
import java.util.Scanner;

public class Client {

  final CoapClient client;

  final CoapClient secureClient;

  public Client(String host) throws Exception {
    client = new CoapClient("coap://" + host + "/test");
    secureClient = new CoapClient("coaps://" + host + "/test");
    secureClient.setEndpoint(new CoapEndpoint(Util.createDtlsConnector("client", 0), NetworkConfig.getStandard()));
  }

  public CoapResponse post(boolean secure) {
    CoapResponse response;
    if (secure) {
      response = secureClient.post("secure", 0);
    } else {
      response = client.post("insecure", 0);
    }

    System.out.println(response.getCode());
    System.out.println(response.getOptions());
    System.out.println(response.getResponseText());

    return response;
  }

  public CoapResponse get(boolean secure) {
    CoapResponse response;
    if (secure) {
      response = secureClient.get();
    } else {
      response = client.get();
    }

    System.out.println(response.getCode());
    System.out.println(response.getOptions());
    System.out.println(response.getResponseText());

    return response;
  }

  public static void main(String[] args) throws Exception {

    Client client = new Client("localhost");

    int r;
    while ((r = System.in.read()) != -1) {
      switch (r) {
        case 'g':
          client.get(true);
          break;
        case 'G':
          client.get(false);
          break;
        case 'p':
          client.post(true);
          break;
        case 'P':
          client.post(false);
          break;
        case 'q':
          System.exit(0);
          break;
        default:
      }
    }

  }
}
