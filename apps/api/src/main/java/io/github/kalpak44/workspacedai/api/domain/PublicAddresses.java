package io.github.kalpak44.workspacedai.api.domain;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Refuses a URL that is not HTTPS on a public address. Where Domain Connect sends us is read from
 * the user's own DNS, so without this a domain could point the API at a service inside the cluster.
 */
final class PublicAddresses {

  private PublicAddresses() {}

  static void require(URI uri) {
    if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
      throw new IllegalArgumentException(uri + " is not an https URL");
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(uri.getHost());
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(uri.getHost() + " does not resolve", e);
    }
    for (var address : addresses) {
      if (!isPublic(address)) {
        throw new IllegalArgumentException(uri.getHost() + " resolves to a private address");
      }
    }
  }

  static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    var bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      // 100.64.0.0/10, carrier-grade NAT, which Java does not count as site-local.
      return !((bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64);
    }
    // fc00::/7, unique local addresses, the IPv6 counterpart of private ranges.
    return !(address instanceof Inet6Address) || (bytes[0] & 0xfe) != 0xfc;
  }
}
