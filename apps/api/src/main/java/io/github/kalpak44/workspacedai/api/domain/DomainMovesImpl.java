package io.github.kalpak44.workspacedai.api.domain;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

class DomainMovesImpl implements DomainMoves {

  private final EntityManager entityManager;

  DomainMovesImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  @Transactional
  public int move(UUID id, DomainStatus from, DomainStatus to, Instant at) {
    var cb = entityManager.getCriteriaBuilder();
    var update = cb.createCriteriaUpdate(WorkspaceDomainEntity.class);
    var domain = update.from(WorkspaceDomainEntity.class);
    update
        .set(domain.get(WorkspaceDomainEntity_.status), to)
        .set(domain.get(WorkspaceDomainEntity_.verifiedAt), at)
        .where(
            cb.equal(domain.get(WorkspaceDomainEntity_.id), id),
            cb.equal(domain.get(WorkspaceDomainEntity_.status), from));
    return entityManager.createQuery(update).executeUpdate();
  }
}
