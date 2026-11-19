package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Allows one verification email a minute per sign-up, across every instance. */
@Component
public class VerificationEmails {

  static final Duration INTERVAL = Duration.ofMinutes(1);
  // Rows older than this decide nothing any more.
  private static final Duration KEPT = Duration.ofDays(1);

  private final EntityManager entityManager;
  private final Clock clock;

  @Autowired
  public VerificationEmails(EntityManager entityManager) {
    this(entityManager, Clock.systemUTC());
  }

  VerificationEmails(EntityManager entityManager, Clock clock) {
    this.entityManager = entityManager;
    this.clock = clock;
  }

  /**
   * Claims the next send for the subject: true when none went out in the last minute. A conditional
   * update moves the time only when it is old enough, so two instances asking at once cannot both
   * win; the first send inserts the row.
   */
  @Transactional
  public boolean claim(String idpSubject) {
    var now = clock.instant();
    var cb = entityManager.getCriteriaBuilder();

    var expired = cb.createCriteriaDelete(VerificationEmailEntity.class);
    var old = expired.from(VerificationEmailEntity.class);
    expired.where(cb.lessThan(old.get(VerificationEmailEntity_.sentAt), now.minus(KEPT)));
    entityManager.createQuery(expired).executeUpdate();

    var update = cb.createCriteriaUpdate(VerificationEmailEntity.class);
    var row = update.from(VerificationEmailEntity.class);
    update
        .set(row.get(VerificationEmailEntity_.sentAt), now)
        .where(
            cb.equal(row.get(VerificationEmailEntity_.idpSubject), idpSubject),
            cb.lessThanOrEqualTo(row.get(VerificationEmailEntity_.sentAt), now.minus(INTERVAL)));
    if (entityManager.createQuery(update).executeUpdate() == 1) {
      return true;
    }
    if (entityManager.find(VerificationEmailEntity.class, idpSubject) != null) {
      return false;
    }
    entityManager.persist(new VerificationEmailEntity(idpSubject, now));
    return true;
  }
}
