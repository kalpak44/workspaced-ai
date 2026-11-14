package io.github.kalpak44.workspacedai.agenthub.agent;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Where enrollment tokens and enrolled agents' public keys are kept. */
public interface AgentStore {

  /** Stores the token, replacing any earlier one for the same VM. */
  void saveToken(EnrollmentToken token);

  /** The VM's token, locked until the transaction ends so two enrolls cannot both use it. */
  Optional<EnrollmentToken> lockToken(UUID vmId);

  void deleteToken(UUID vmId);

  boolean isEnrolled(UUID vmId);

  /**
   * @return false when the VM already has an agent, which is then left as it was
   */
  boolean saveAgent(UUID vmId, byte[] publicKey, Instant enrolledAt);

  /** The raw 32-byte Ed25519 public key the VM enrolled with. */
  Optional<byte[]> publicKey(UUID vmId);

  void touch(UUID vmId, Instant seenAt);

  /** Forgets the VM's agent and any token still pending for it. */
  void delete(UUID vmId);
}
