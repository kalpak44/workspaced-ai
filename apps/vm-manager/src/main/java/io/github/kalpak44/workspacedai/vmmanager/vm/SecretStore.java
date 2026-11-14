package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Where each VM's secrets are kept: Vault, in the cluster. Each VM has two entries, so the harness
 * environment the API hands over can never be listed, counted or removed as a user secret.
 *
 * @see io.github.kalpak44.workspacedai.vmmanager.vault.VaultSecretStore
 */
public interface SecretStore {

  /** One of a VM's two entries. */
  enum Entry {
    /** The user's secrets and the model provider's key. */
    SECRETS,
    /** The harness's system environment, handed over by the API when the VM is created. */
    SYSTEM
  }

  /** Its values by name; empty when the entry does not exist. */
  Map<String, String> read(UUID vmId, Entry entry);

  /**
   * Replaces the entry with what {@code change} makes of it, atomically: when another change lands
   * in between, {@code change} runs again on the newer one. An exception thrown by {@code change}
   * leaves it as it was, and a change that alters an existing entry in no way writes nothing.
   *
   * @return what the entry holds afterwards
   */
  Map<String, String> update(UUID vmId, Entry entry, UnaryOperator<Map<String, String>> change);

  /** Removes both entries, earlier values included; one that does not exist is not an error. */
  void deleteAll(UUID vmId);
}
