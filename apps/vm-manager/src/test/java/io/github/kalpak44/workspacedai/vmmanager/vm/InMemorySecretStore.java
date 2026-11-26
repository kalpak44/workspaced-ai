package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Vault kept in maps, one per entry. Setting {@link #failure} makes every call throw it instead.
 */
class InMemorySecretStore implements SecretStore {

  final Map<UUID, Map<String, String>> entries = new ConcurrentHashMap<>();
  final Map<UUID, Map<String, String>> system = new ConcurrentHashMap<>();
  final Map<UUID, Map<String, String>> firewall = new ConcurrentHashMap<>();
  volatile RuntimeException failure;

  /** Fails only writes to the system entry, so the first of two writes can land. */
  volatile RuntimeException systemWriteFailure;

  @Override
  public Map<String, String> read(UUID vmId, Entry entry) {
    fail();
    return of(entry).getOrDefault(vmId, Map.of());
  }

  @Override
  public synchronized Map<String, String> update(
      UUID vmId, Entry entry, UnaryOperator<Map<String, String>> change) {
    fail();
    if (entry == Entry.SYSTEM && systemWriteFailure != null) {
      throw systemWriteFailure;
    }
    var next = Map.copyOf(change.apply(of(entry).getOrDefault(vmId, Map.of())));
    of(entry).put(vmId, next);
    return next;
  }

  @Override
  public void deleteAll(UUID vmId) {
    fail();
    entries.remove(vmId);
    system.remove(vmId);
    firewall.remove(vmId);
  }

  private Map<UUID, Map<String, String>> of(Entry entry) {
    return switch (entry) {
      case SECRETS -> entries;
      case SYSTEM -> system;
      case FIREWALL -> firewall;
    };
  }

  private void fail() {
    if (failure != null) {
      throw failure;
    }
  }
}
