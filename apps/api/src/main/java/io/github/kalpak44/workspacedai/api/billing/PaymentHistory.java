package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** What other parts of the API may know of accounts' payments, without reaching into billing. */
@Component
public class PaymentHistory {

  // Keeps each IN list well under PostgreSQL's limit on parameters in one statement.
  private static final int BATCH = 1000;

  private final EntityManager entities;

  public PaymentHistory(EntityManager entities) {
    this.entities = entities;
  }

  /** Those of the accounts that have paid at least one top-up. */
  @Transactional(readOnly = true)
  public Set<UUID> paid(Collection<UUID> users) {
    var cb = entities.getCriteriaBuilder();
    var paid = new HashSet<UUID>();
    for (var batch : batches(users)) {
      var query = cb.createQuery(UUID.class);
      var topUp = query.from(TopUpEntity.class);
      var user = topUp.get(TopUpEntity_.userId);
      query
          .select(user)
          .distinct(true)
          .where(user.in(batch), cb.equal(topUp.get(TopUpEntity_.status), TopUpStatus.PAID));
      paid.addAll(entities.createQuery(query).getResultList());
    }
    return paid;
  }

  /** When each of the accounts was last charged for a workspace; absent for one never charged. */
  @Transactional(readOnly = true)
  public Map<UUID, Instant> lastCharged(Collection<UUID> users) {
    var cb = entities.getCriteriaBuilder();
    var last = new HashMap<UUID, Instant>();
    for (var batch : batches(users)) {
      var query = cb.createTupleQuery();
      var entry = query.from(LedgerEntryEntity.class);
      var user = entry.get(LedgerEntryEntity_.userId);
      var latest = cb.greatest(entry.get(LedgerEntryEntity_.createdAt));
      query
          .multiselect(user, latest)
          .where(user.in(batch), cb.equal(entry.get(LedgerEntryEntity_.kind), Ledger.USAGE))
          .groupBy(user);
      for (var row : entities.createQuery(query).getResultList()) {
        last.put(row.get(user), row.get(latest));
      }
    }
    return last;
  }

  private static List<List<UUID>> batches(Collection<UUID> users) {
    var all = List.copyOf(users);
    var batches = new ArrayList<List<UUID>>();
    for (var i = 0; i < all.size(); i += BATCH) {
      batches.add(all.subList(i, Math.min(i + BATCH, all.size())));
    }
    return batches;
  }
}
