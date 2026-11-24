package io.github.kalpak44.workspacedai.agenthub.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps both tables in the API's PostgreSQL database, which creates them in its migrations.
 *
 * <p>Every update and delete goes through a row locked first, so a row a concurrent transaction
 * deleted is skipped rather than failing the flush or being inserted again.
 */
@Repository
@Transactional
class JpaAgentStore implements AgentStore {

  private final EnrollmentTokenRepository tokens;
  private final AgentRepository agents;
  private final Clock clock;

  JpaAgentStore(EnrollmentTokenRepository tokens, AgentRepository agents, Clock clock) {
    this.tokens = tokens;
    this.agents = agents;
    this.clock = clock;
  }

  /**
   * Two first tokens for one VM both find no row to lock, so one insert fails on the key; {@link
   * EnrollmentService#issue} runs again and then replaces the winner's row.
   */
  @Override
  public void saveToken(EnrollmentToken token) {
    var entity =
        tokens
            .findLockedByVmId(token.vmId())
            .orElseGet(() -> new EnrollmentTokenEntity(token.vmId()));
    entity.replace(token, clock.instant());
    tokens.saveAndFlush(entity);
  }

  @Override
  public Optional<EnrollmentToken> lockToken(UUID vmId) {
    return tokens.findLockedByVmId(vmId).map(EnrollmentTokenEntity::toToken);
  }

  @Override
  public void deleteToken(UUID vmId) {
    tokens.findLockedByVmId(vmId).ifPresent(tokens::delete);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isEnrolled(UUID vmId) {
    return agents.existsById(vmId);
  }

  // Only a transaction holding the VM's token lock calls this, so no second agent can be
  // inserted between the check and the insert.
  @Override
  public boolean saveAgent(UUID vmId, byte[] publicKey, Instant enrolledAt) {
    if (agents.existsById(vmId)) {
      return false;
    }
    agents.save(new AgentEntity(vmId, publicKey, enrolledAt));
    return true;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<byte[]> publicKey(UUID vmId) {
    return agents.findById(vmId).map(AgentEntity::getPublicKey);
  }

  @Override
  public void touch(UUID vmId, Instant seenAt) {
    agents.findLockedByVmId(vmId).ifPresent(agent -> agent.seen(seenAt));
  }

  @Override
  public void delete(UUID vmId) {
    deleteToken(vmId);
    agents.findLockedByVmId(vmId).ifPresent(agents::delete);
  }
}
