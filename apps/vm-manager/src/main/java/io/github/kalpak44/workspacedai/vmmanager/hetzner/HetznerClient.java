package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.util.List;

/**
 * The Hetzner Cloud API, as far as this app uses it. An interface so the services above it are
 * tested against a stand-in rather than the network.
 *
 * @see HttpHetznerClient
 */
public interface HetznerClient {

  /**
   * Every server type the project can see.
   *
   * @throws HetznerException.NotConfigured when no token is configured
   * @throws HetznerException when Hetzner fails or answers unexpectedly
   */
  List<ServerType> serverTypes();
}
