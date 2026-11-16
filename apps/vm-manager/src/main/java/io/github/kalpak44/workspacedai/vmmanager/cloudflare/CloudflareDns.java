package io.github.kalpak44.workspacedai.vmmanager.cloudflare;

import java.util.List;

/**
 * Cloudflare's DNS records API for one zone, as far as this app uses it. An interface so the
 * services above it are tested against a stand-in rather than the network. Every call but {@link
 * #isConfigured} throws {@link CloudflareException.NotConfigured} when no token or zone is
 * configured, and {@link CloudflareException} when Cloudflare fails or refuses.
 *
 * @see HttpCloudflareDns
 */
public interface CloudflareDns {

  boolean isConfigured();

  /** Every record whose name ends with the suffix, across all of Cloudflare's pages. */
  List<DnsRecord> records(String nameSuffix);

  /** DNS-only, so a request to the name goes straight to the address rather than through us. */
  void create(String type, String name, String content, int ttl);

  void update(String id, String content);

  /** A record already gone counts as deleted, so a retried delete succeeds. */
  void delete(String id);
}
