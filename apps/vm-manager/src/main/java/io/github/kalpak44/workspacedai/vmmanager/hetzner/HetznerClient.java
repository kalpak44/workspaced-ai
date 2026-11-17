package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The Hetzner Cloud API, as far as this app uses it. An interface so the services above it are
 * tested against a stand-in rather than the network. Every method throws {@link
 * HetznerException.NotConfigured} when no token is configured, and {@link HetznerException} when
 * Hetzner fails or answers unexpectedly.
 *
 * @see HttpHetznerClient
 */
public interface HetznerClient {

  /** Every server type the project can see. */
  List<ServerType> serverTypes();

  /** What a primary IPv4 address costs a month in each location, which a server type leaves out. */
  List<ServerType.Price> primaryIpv4Prices();

  /**
   * Every server carrying the label, e.g. {@code managed-by=x}, across all of Hetzner's pages.
   *
   * @param labelSelector in Hetzner's label selector syntax
   */
  List<Server> servers(String labelSelector);

  /** Returns as soon as Hetzner accepts it; the server then boots on its own. */
  CreatedServer createServer(ServerSpec spec);

  /** A server already gone counts as deleted, so a retried delete succeeds. */
  void deleteServer(long id);

  /**
   * Asks the server's operating system to shut down, which keeps its disk; returns as soon as
   * Hetzner accepts it. Hetzner bills a server that is off as one that is running.
   */
  void shutdownServer(long id);

  /** Powers a server that is off back on; returns as soon as Hetzner accepts it. */
  void powerOnServer(long id);

  /** A firewall with no rules, which lets nothing in; Hetzner refuses a name already taken. */
  long createFirewall(String name, Map<String, String> labels);

  /**
   * Every firewall carrying the label, across all of Hetzner's pages.
   *
   * @param labelSelector in Hetzner's label selector syntax
   */
  List<Firewall> firewalls(String labelSelector);

  /** Replaces every rule at once; Hetzner has no call that changes one rule alone. */
  void setFirewallRules(long id, List<FirewallRule> rules);

  /**
   * A firewall already gone counts as deleted. Hetzner refuses one that still applies to a server,
   * which it keeps doing for a while after that server is deleted.
   */
  void deleteFirewall(long id);

  /**
   * The server's CPU, disk and network series over the period. Hetzner keeps 30 days of them.
   *
   * @param step how far apart the samples are; Hetzner widens it to keep under 500 samples
   */
  ServerMetrics metrics(long id, Instant start, Instant end, Duration step);
}
