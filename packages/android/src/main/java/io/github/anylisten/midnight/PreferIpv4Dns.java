package io.github.anylisten.midnight;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Dns;

/**
 * Prefers IPv4 addresses ahead of IPv6 when both are returned by system DNS, avoiding multi-second
 * connect stalls on broken mobile/Wi-Fi IPv6 routes while preserving IPv6-only connectivity.
 */
final class PreferIpv4Dns implements Dns {
  static final PreferIpv4Dns INSTANCE = new PreferIpv4Dns(Dns.SYSTEM);
  private final Dns delegate;

  PreferIpv4Dns(Dns delegate) {
    this.delegate = delegate;
  }

  @Override
  public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    return order(delegate.lookup(hostname));
  }

  static List<InetAddress> order(List<InetAddress> addresses) {
    if (addresses.size() <= 1) return addresses;
    List<InetAddress> ipv4 = new ArrayList<>(addresses.size());
    List<InetAddress> other = new ArrayList<>();
    for (InetAddress address : addresses) {
      if (address instanceof Inet4Address) ipv4.add(address);
      else other.add(address);
    }
    if (ipv4.isEmpty() || other.isEmpty()) return addresses;
    ipv4.addAll(other);
    return ipv4;
  }
}
