package io.github.kalpak44.workspacedai.api.funnel;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.TourEventEntity;
import io.github.kalpak44.workspacedai.api.auth.TourEventRepository;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.billing.PaymentHistory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts the funnel from facts the database already holds, so an ad blocker hides none of it: the
 * account, its demo, its paid top-ups, and the hourly charges a kept workspace leaves.
 */
@Service
public class FunnelService {

  private static final Duration RETENTION = Duration.ofDays(30);
  // Keeps each IN list well under PostgreSQL's limit on parameters in one statement.
  private static final int BATCH = 1000;

  private final UserRepository users;
  private final TourEventRepository tourEvents;
  private final PaymentHistory payments;

  public FunnelService(
      UserRepository users, TourEventRepository tourEvents, PaymentHistory payments) {
    this.users = users;
    this.tourEvents = tourEvents;
    this.payments = payments;
  }

  /**
   * @param from first day of sign-ups, UTC
   * @param to last day of sign-ups, UTC, included
   */
  @Transactional(readOnly = true)
  public Funnel funnel(LocalDate from, LocalDate to) {
    return funnel(from, to, Instant.now());
  }

  @Transactional(readOnly = true)
  Funnel funnel(LocalDate from, LocalDate to, Instant now) {
    // Admins are left out: they pay cost price and try everything.
    var cohort =
        users.findByRoleNotAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Role.ADMIN,
            from.atStartOfDay(ZoneOffset.UTC).toInstant(),
            to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    var ids = cohort.stream().map(UserEntity::getId).toList();
    var paid = payments.paid(ids);
    var lastCharged = payments.lastCharged(ids);

    var total = new Tally();
    var bySource = new LinkedHashMap<SourceKey, Tally>();
    for (var user : cohort) {
      var key = new SourceKey(user.getSignupSource(), user.getSignupCampaign());
      var tally = bySource.computeIfAbsent(key, _ -> new Tally());
      for (var counts : List.of(total, tally)) {
        counts.add(user, paid.contains(user.getId()), lastCharged.get(user.getId()), now);
      }
    }
    var sources = new ArrayList<Funnel.Source>();
    bySource.forEach(
        (key, tally) ->
            sources.add(new Funnel.Source(key.source(), key.campaign(), tally.counts())));
    sources.sort(
        Comparator.comparingLong((Funnel.Source s) -> s.counts().signedUp())
            .reversed()
            .thenComparing(Funnel.Source::source, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                Funnel.Source::campaign, Comparator.nullsLast(Comparator.naturalOrder())));
    return new Funnel(from, to, total.counts(), sources, tour(ids));
  }

  /** How many reached each step, and where each account's tour is now. */
  private List<Funnel.TourStep> tour(List<UUID> ids) {
    var reached = new TreeMap<Integer, Set<UUID>>();
    var latest = new HashMap<UUID, TourEventEntity>();
    for (var batch : batches(ids)) {
      for (var event : tourEvents.findByUserIdInOrderByIdAsc(batch)) {
        if (event.getKind() == TourEventEntity.Kind.REACHED) {
          reached.computeIfAbsent(event.getStep(), _ -> new HashSet<>()).add(event.getUserId());
        }
        latest.put(event.getUserId(), event);
      }
    }
    var now = new TreeMap<Integer, Map<TourEventEntity.Kind, Long>>();
    latest
        .values()
        .forEach(
            event ->
                now.computeIfAbsent(event.getStep(), _ -> new HashMap<>())
                    .merge(event.getKind(), 1L, Long::sum));
    var steps = new TreeMap<Integer, Funnel.TourStep>();
    var all = new HashSet<>(reached.keySet());
    all.addAll(now.keySet());
    for (var step : all) {
      var here = now.getOrDefault(step, Map.of());
      steps.put(
          step,
          new Funnel.TourStep(
              step,
              reached.getOrDefault(step, Set.of()).size(),
              here.getOrDefault(TourEventEntity.Kind.REACHED, 0L)
                  + here.getOrDefault(TourEventEntity.Kind.RESUMED, 0L),
              here.getOrDefault(TourEventEntity.Kind.PAUSED, 0L),
              here.getOrDefault(TourEventEntity.Kind.ENDED, 0L)));
    }
    return List.copyOf(steps.values());
  }

  private static List<List<UUID>> batches(List<UUID> ids) {
    var batches = new ArrayList<List<UUID>>();
    for (var i = 0; i < ids.size(); i += BATCH) {
      batches.add(ids.subList(i, Math.min(i + BATCH, ids.size())));
    }
    return batches;
  }

  private record SourceKey(String source, String campaign) {}

  /** The counts for one group of accounts, added up one account at a time. */
  private static final class Tally {
    private long signedUp;
    private long demoStarted;
    private long paid;
    private long retentionEligible;
    private long retained;

    void add(UserEntity user, boolean hasPaid, Instant lastCharged, Instant now) {
      signedUp++;
      if (user.getDemoStartedAt() != null) {
        demoStarted++;
      }
      if (hasPaid) {
        paid++;
      }
      var due = user.getCreatedAt().plus(RETENTION);
      if (!due.isAfter(now)) {
        retentionEligible++;
      }
      if (lastCharged != null && !lastCharged.isBefore(due)) {
        retained++;
      }
    }

    Funnel.Counts counts() {
      return new Funnel.Counts(signedUp, demoStarted, paid, retentionEligible, retained);
    }
  }
}
