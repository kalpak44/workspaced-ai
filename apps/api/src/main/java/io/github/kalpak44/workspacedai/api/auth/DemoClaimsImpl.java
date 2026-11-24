package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

class DemoClaimsImpl implements DemoClaims {

  private final EntityManager entityManager;

  DemoClaimsImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  @Transactional
  public int claimDemo(UUID id, Instant now) {
    var cb = entityManager.getCriteriaBuilder();
    var update = cb.createCriteriaUpdate(UserEntity.class);
    var user = update.from(UserEntity.class);
    update
        .set(user.get(UserEntity_.demoStartedAt), now)
        .where(
            cb.equal(user.get(UserEntity_.id), id), cb.isNull(user.get(UserEntity_.demoStartedAt)));
    return entityManager.createQuery(update).executeUpdate();
  }

  @Override
  @Transactional
  public int releaseDemo(UUID id) {
    var cb = entityManager.getCriteriaBuilder();
    var update = cb.createCriteriaUpdate(UserEntity.class);
    var user = update.from(UserEntity.class);
    update
        .set(user.get(UserEntity_.demoStartedAt), cb.nullLiteral(Instant.class))
        .where(cb.equal(user.get(UserEntity_.id), id));
    return entityManager.createQuery(update).executeUpdate();
  }
}
