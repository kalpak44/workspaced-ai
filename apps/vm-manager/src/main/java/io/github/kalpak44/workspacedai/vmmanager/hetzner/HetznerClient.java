package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.util.List;

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
}
