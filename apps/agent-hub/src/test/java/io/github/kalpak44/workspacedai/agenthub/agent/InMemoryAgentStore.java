package io.github.kalpak44.workspacedai.agenthub.agent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Both tables as maps, for services tested without a database. */
public class InMemoryAgentStore implements AgentStore {

  public final Map<UUID, EnrollmentToken> tokens = new ConcurrentHashMap<>();
  public final Map<UUID, byte[]> keys = new ConcurrentHashMap<>();
  public final Map<UUID, Instant> lastSeen = new ConcurrentHashMap<>();

  @Override
  public void saveToken(EnrollmentToken token) {
    tokens.put(token.vmId(), token);
  }

  @Override
  public Optional<EnrollmentToken> lockToken(UUID vmId) {
    return Optional.ofNullable(tokens.get(vmId));
  }

  @Override
  public void deleteToken(UUID vmId) {
    tokens.remove(vmId);
  }

  @Override
  public boolean isEnrolled(UUID vmId) {
    return keys.containsKey(vmId);
  }

  @Override
  public boolean saveAgent(UUID vmId, byte[] publicKey, Instant enrolledAt) {
    return keys.putIfAbsent(vmId, publicKey) == null;
  }

  @Override
  public Optional<byte[]> publicKey(UUID vmId) {
    return Optional.ofNullable(keys.get(vmId));
  }

  @Override
  public void touch(UUID vmId, Instant seenAt) {
    if (keys.containsKey(vmId)) {
      lastSeen.put(vmId, seenAt);
    }
  }

  @Override
  public void delete(UUID vmId) {
    tokens.remove(vmId);
    keys.remove(vmId);
    lastSeen.remove(vmId);
  }
}
