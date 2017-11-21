package eu.swdev.ttest;

import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.pskstore.InMemoryPskStore;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;

public class Util {

  private static final String KEY_STORE_PASSWORD = "endPass";
  private static final String KEY_STORE_LOCATION = "certs/keyStore.jks";
  private static final String TRUST_STORE_PASSWORD = "rootPass";
  private static final String TRUST_STORE_LOCATION = "certs/trustStore.jks";

  public static DTLSConnector createDtlsConnector(String clientOrServer, int port) throws Exception {
    InMemoryPskStore pskStore = new InMemoryPskStore();
    // put in the PSK store the default identity/psk for tinydtls tests
    pskStore.setKey("Client_identity", "secretPSK".getBytes());
    InputStream in = null;
    // load the key store
    KeyStore keyStore = KeyStore.getInstance("JKS");
    in = Util.class.getClassLoader().getResourceAsStream(KEY_STORE_LOCATION);
    keyStore.load(in, KEY_STORE_PASSWORD.toCharArray());
    in.close();

    // load the trust store
    KeyStore trustStore = KeyStore.getInstance("JKS");
    InputStream inTrust = Util.class.getClassLoader().getResourceAsStream(TRUST_STORE_LOCATION);
    trustStore.load(inTrust, TRUST_STORE_PASSWORD.toCharArray());
    inTrust.close();

    // You can load multiple certificates if needed
    Certificate[] trustedCertificates = new Certificate[1];
    trustedCertificates[0] = trustStore.getCertificate("root");

    DtlsConnectorConfig.Builder builder = new DtlsConnectorConfig.Builder();
    builder.setAddress(new InetSocketAddress(port));
    builder.setPskStore(pskStore);
    builder.setIdentity((PrivateKey) keyStore.getKey(clientOrServer, KEY_STORE_PASSWORD.toCharArray()),
        keyStore.getCertificateChain(clientOrServer), true);
    builder.setTrustStore(trustedCertificates);
    return new DTLSConnector(builder.build());

  }
}
