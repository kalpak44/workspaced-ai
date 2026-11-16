package io.github.kalpak44.workspacedai.api.domain;

import java.util.List;
import java.util.Optional;

/**
 * Public DNS, as far as attaching a domain needs it. An interface so the service is tested without
 * the network. A name that does not exist answers empty; {@link DnsLookupException} is only for a
 * resolver that could not be asked.
 *
 * @see JndiDnsLookup
 */
public interface DnsLookup {

  /** The zone the name lives in: itself or the nearest parent with an SOA record. */
  Optional<String> zoneOf(String name);

  /** Each TXT record's text, its strings joined. */
  List<String> txt(String name);

  /** The IPv4 addresses the name resolves to, following any CNAME. */
  List<String> ipv4(String name);
}
