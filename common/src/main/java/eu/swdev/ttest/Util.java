package eu.swdev.ttest;

import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.elements.tcp.TcpClientConnector;
import org.eclipse.californium.elements.tcp.TlsClientConnector;
import org.eclipse.californium.elements.tcp.TlsServerConnector;
import org.eclipse.californium.elements.util.SslContextUtil;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.pskstore.InMemoryPskStore;

import javax.net.ssl.SSLContext;
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

  public static NetworkConfig networkConfig = NetworkConfig
      .createStandardWithoutFile()
      .setInt(NetworkConfig.Keys.MAX_RESOURCE_BODY_SIZE, 500000)
      .setInt(NetworkConfig.Keys.BLOCKWISE_STATUS_LIFETIME, 5 * 60 * 1000);

  public static DTLSConnector createDtlsServerConnector(InetSocketAddress addr) {
    DtlsConnectorConfig.Builder builder = new DtlsConnectorConfig.Builder();
    builder.setAddress(addr);
    try {
      InMemoryPskStore pskStore = new InMemoryPskStore();
      // put in the PSK store the default identity/psk for tinydtls tests
      pskStore.setKey("Client_identity", "secretPSK".getBytes());

      builder.setPskStore(pskStore);

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
      builder.setIdentity((PrivateKey) keyStore.getKey("server", KEY_STORE_PASSWORD.toCharArray()),
          keyStore.getCertificateChain("server"), true);
      builder.setTrustStore(trustedCertificates);
      return new DTLSConnector(builder.build());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }

  public static DTLSConnector createDtlsClientConnector(InetSocketAddress addr, DtlsSecurity security) {
    DtlsConnectorConfig.Builder builder = new DtlsConnectorConfig.Builder();
    builder.setAddress(addr);
    try {
      if (security.handshake != Handshake.PSK) {

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
        builder.setIdentity((PrivateKey) keyStore.getKey(security.alias, KEY_STORE_PASSWORD.toCharArray()),
            keyStore.getCertificateChain(security.alias), security.handshake == Handshake.RPK);
        builder.setTrustStore(trustedCertificates);
        builder.setSupportedCipherSuites(new CipherSuite[]{CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256});

      } else {

        InMemoryPskStore pskStore = new InMemoryPskStore() {
          @Override
          public String getIdentity(InetSocketAddress inetAddress) {
            return "Client_identity";
          }
        };
        // put in the PSK store the default identity/psk for tinydtls tests
        pskStore.setKey("Client_identity", "secretPSK".getBytes());

        builder.setPskStore(pskStore);
        builder.setSupportedCipherSuites(new CipherSuite[]{CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA256});

      }
      return new DTLSConnector(builder.build());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }

  public static SSLContext createSslContext(String clientOrServer) {
    try {
      SslContextUtil.Credentials credentials = SslContextUtil.loadCredentials(
          "classpath://certs/keyStore.jks",
          clientOrServer,
          "endPass".toCharArray(),
          "endPass".toCharArray());

      Certificate[] trustedCerts = SslContextUtil.loadTrustedCertificates(
          "classpath://certs/trustStore.jks",
          null,   // load all certs in trust store
          "rootPass".toCharArray());


      return SslContextUtil.createSSLContext(clientOrServer,
          credentials.getPrivateKey(),
          credentials.getCertificateChain(),
          trustedCerts);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static TcpClientConnector createTcpClientConnector() {
    return new TcpClientConnector(2, 5000, 10000);
  }


  public static TlsClientConnector createTlsClientConnector() {
    SSLContext sslContext = createSslContext("client");
    return new TlsClientConnector(sslContext, 2, 5000, 10000);
  }

  public static TlsServerConnector createTlsServerConnector(int port) {
    SSLContext sslContext = createSslContext("server");
    return new TlsServerConnector(sslContext, new InetSocketAddress(port), 1, 5000);
  }
}
