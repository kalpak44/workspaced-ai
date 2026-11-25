package io.github.kalpak44.workspacedai.api.auth;

import io.github.kalpak44.workspacedai.api.provider.OpenRouterManagement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One lifetime-funded key per account. A row lock serializes signup, list and fallback requests.
 */
@Service
public class TrialConnectors {
  private static final Logger LOG = LoggerFactory.getLogger(TrialConnectors.class);
  private final UserRepository users;
  private final AiConnectorRepository connectors;
  private final OpenRouterManagement management;
  private final SecretBox box;
  private final TransactionTemplate transaction;

  public TrialConnectors(
      UserRepository users,
      AiConnectorRepository connectors,
      OpenRouterManagement management,
      AuthProperties properties,
      PlatformTransactionManager manager) {
    this.users = users;
    this.connectors = connectors;
    this.management = management;
    box = new SecretBox(properties.jwtSecret(), "workspaced-ai-connectors");
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Optional<AiConnectorEntity> ensure(UUID ownerId) {
    var existing = connectors.findByOwnerIdAndTrialTrue(ownerId);
    if (existing.isPresent() || !management.configured()) return existing;
    var issued = new AtomicReference<OpenRouterManagement.IssuedKey>();
    try {
      return transaction.execute(
          status -> {
            var owner = users.findLockedById(ownerId);
            if (owner.filter(UserEntity::hasFreeTrial).isEmpty()) return Optional.empty();
            var current = connectors.findByOwnerIdAndTrialTrue(ownerId);
            if (current.isPresent()) return current;
            var key = management.createTrial(ownerId);
            issued.set(key);
            return Optional.of(
                connectors.saveAndFlush(
                    AiConnectorEntity.trial(ownerId, box.seal(key.value()), key.hash())));
          });
    } catch (RuntimeException failure) {
      // A DB failure must not leave an issued key usable outside the account's one saved grant.
      if (issued.get() != null) {
        try {
          management.revoke(issued.get().hash());
        } catch (RuntimeException cleanup) {
          LOG.error("trial connector cleanup failed for user {}", ownerId);
        }
      }
      throw failure;
    }
  }

  /** Provisioning outages must not prevent signing in or showing the user's personal connectors. */
  public void tryEnsure(UUID ownerId) {
    try {
      ensure(ownerId);
    } catch (RuntimeException failure) {
      LOG.warn("trial connector provisioning unavailable for user {}", ownerId);
    }
  }
}
