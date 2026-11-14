package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Where each VM's secrets are kept: Vault, in the cluster. Values go in and never come back out
 * through this app; only names are read.
 *
 * @see io.github.kalpak44.workspacedai.vmmanager.vault.VaultSecretStore
 */
public interface SecretStore {

  /** Its secrets' names, in no particular order; empty when it has none. */
  Set<String> names(UUID vmId);

  /**
   * Replaces its secrets with what {@code change} makes of the current ones, atomically: when
   * another change lands in between, {@code change} runs again on the newer ones. An exception
   * thrown by {@code change} leaves them as they were.
   */
  void update(UUID vmId, UnaryOperator<Map<String, String>> change);

  /** Removes every secret it has, earlier values included; one with none is not an error. */
  void deleteAll(UUID vmId);
}
