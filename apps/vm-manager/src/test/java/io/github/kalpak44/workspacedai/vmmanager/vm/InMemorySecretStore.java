package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** Vault kept in a map. Setting {@link #failure} makes every call throw it instead. */
class InMemorySecretStore implements SecretStore {

  final Map<UUID, Map<String, String>> entries = new ConcurrentHashMap<>();
  volatile RuntimeException failure;

  @Override
  public Set<String> names(UUID vmId) {
    fail();
    return entries.getOrDefault(vmId, Map.of()).keySet();
  }

  @Override
  public synchronized void update(UUID vmId, UnaryOperator<Map<String, String>> change) {
    fail();
    entries.put(vmId, Map.copyOf(change.apply(entries.getOrDefault(vmId, Map.of()))));
  }

  @Override
  public void deleteAll(UUID vmId) {
    fail();
    entries.remove(vmId);
  }

  private void fail() {
    if (failure != null) {
      throw failure;
    }
  }
}
