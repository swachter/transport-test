package eu.swdev.ttest;

public enum DtlsSecurity {

  SERVER("server", Handshake.X509),
  CLIENT_PSK("client", Handshake.PSK),
  CLIENT_RPK("client", Handshake.RPK),
  CLIENT_X509("client", Handshake.X509);

  public final String alias;
  public final Handshake handshake;

  DtlsSecurity(String alias, Handshake handshake) {
    this.alias = alias;
    this.handshake = handshake;
  }

}
