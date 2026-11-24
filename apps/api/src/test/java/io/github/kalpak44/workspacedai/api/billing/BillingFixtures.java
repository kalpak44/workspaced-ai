package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;

/** Billing rows for tests outside this package, which cannot reach its entities. */
public final class BillingFixtures {

  private BillingFixtures() {}

  /** A €10 top-up Stripe has paid. Call inside a transaction. */
  public static void paidTopUp(EntityManager entityManager, UUID user) {
    var topUp = new TopUpEntity(UUID.randomUUID(), user, 1000);
    topUp.markPaid(Instant.now(), null, null);
    entityManager.persist(topUp);
  }

  /** An hourly charge for a workspace, as if made at the given time. Call inside a transaction. */
  public static void usageCharge(EntityManager entityManager, UUID user, Instant at) {
    var reference = "usage-" + UUID.randomUUID();
    entityManager.persist(new LedgerEntryEntity(user, -10_000, Ledger.USAGE, reference));
    entityManager.flush();
    // The database stamps created_at, so backdating it takes an update.
    var cb = entityManager.getCriteriaBuilder();
    var update = cb.createCriteriaUpdate(LedgerEntryEntity.class);
    var row = update.from(LedgerEntryEntity.class);
    update
        .set(row.get(LedgerEntryEntity_.createdAt), at)
        .where(cb.equal(row.get(LedgerEntryEntity_.reference), reference));
    entityManager.createQuery(update).executeUpdate();
  }

  /** How many usage rows bill the workspace by the hour. */
  public static long usageRows(EntityManager entityManager, UUID workspace) {
    var cb = entityManager.getCriteriaBuilder();
    var query = cb.createQuery(Long.class);
    var usage = query.from(UsageEntity.class);
    query.select(cb.count(usage)).where(cb.equal(usage.get(UsageEntity_.workspaceId), workspace));
    return entityManager.createQuery(query).getSingleResult();
  }
}
