package io.github.kalpak44.workspacedai.agenthub.agent;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues single-use enrollment tokens and trades one for the public key a VM enrolls with. */
@Service
public class EnrollmentService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final AgentStore store;
  private final AgentHubProperties properties;
  private final Clock clock;

  public EnrollmentService(AgentStore store, AgentHubProperties properties, Clock clock) {
    this.store = store;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Replaces any token issued earlier for the VM, so only the one in its newest user data works.
   *
   * @throws AlreadyEnrolledException the VM already has an agent
   */
  @Transactional
  public IssuedToken issue(UUID vmId) {
    if (store.isEnrolled(vmId)) {
      throw new AlreadyEnrolledException(vmId);
    }
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var expiresAt = clock.instant().plus(properties.enrollmentTtl());
    store.saveToken(new EnrollmentToken(vmId, hash(token), expiresAt));
    return new IssuedToken(vmId, token, expiresAt);
  }

  /**
   * Deleting the token and storing the key commit together, so a token can never be used twice nor
   * lost without its key landing.
   *
   * @param publicKey base64 (standard, padded) of the raw 32-byte Ed25519 public key
   * @throws MalformedKeyException the key is not one
   * @throws AlreadyEnrolledException the VM already has an agent
   * @throws EnrollmentRefusedException the token is unknown, expired, used, or another VM's
   */
  @Transactional
  public void enroll(UUID vmId, String token, String publicKey) {
    var key = decodeKey(publicKey);
    if (store.isEnrolled(vmId)) {
      throw new AlreadyEnrolledException(vmId);
    }
    var stored = store.lockToken(vmId).orElseThrow(EnrollmentRefusedException::new);
    // Constant time, so response timing reveals nothing of the stored hash.
    if (!MessageDigest.isEqual(stored.hash(), hash(token))
        || !clock.instant().isBefore(stored.expiresAt())) {
      throw new EnrollmentRefusedException();
    }
    store.deleteToken(vmId);
    if (!store.saveAgent(vmId, key, clock.instant())) {
      throw new AlreadyEnrolledException(vmId);
    }
  }

  /** Forgets the VM's agent and any pending token; nothing to forget is not an error. */
  @Transactional
  public void revoke(UUID vmId) {
    store.delete(vmId);
  }

  private static byte[] decodeKey(String publicKey) {
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(publicKey);
    } catch (IllegalArgumentException e) {
      throw new MalformedKeyException("the public key is not base64", e);
    }
    Ed25519Keys.decode(raw);
    return raw;
  }

  private static byte[] hash(String token) {
    return Hashes.sha256(token.getBytes(StandardCharsets.UTF_8));
  }
}
