package io.github.kalpak44.workspacedai.agenthub.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.agenthub.TestCallers;
import io.github.kalpak44.workspacedai.agenthub.TestDatabase;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;

/** The store against a real PostgreSQL, with the tables made as the API's migration makes them. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestCallers.class})
class JpaAgentStoreTest {

  private static final Instant EXPIRES = Instant.parse("2026-11-14T10:30:00Z");

  @Autowired JpaAgentStore store;
  @Autowired EnrollmentService enrollments;
  @Autowired TransactionOperations transactions;
  @Autowired EnrollmentTokenRepository tokens;
  @Autowired AgentRepository agents;
  @Autowired EntityManager entities;

  private final UUID vm = UUID.randomUUID();

  @Test
  void savingATokenReplacesTheEarlierOneAndRestartsItsAge() {
    store.saveToken(new EnrollmentToken(vm, new byte[] {1}, EXPIRES));
    var earlier = Instant.parse("2020-01-01T00:00:00Z");
    transactions.executeWithoutResult(_ -> backdate(earlier));
    assertThat(createdAt()).isEqualTo(earlier);

    store.saveToken(new EnrollmentToken(vm, new byte[] {2}, EXPIRES.plusSeconds(60)));

    var token = lockedToken().orElseThrow();
    assertThat(token.hash()).containsExactly(2);
    assertThat(token.expiresAt()).isEqualTo(EXPIRES.plusSeconds(60));
    assertThat(createdAt()).isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
    assertThat(tokens.findAll()).filteredOn(row -> row.toToken().vmId().equals(vm)).hasSize(1);
  }

  @Test
  void issuingConcurrentlyForANewVmLeavesOneToken() throws Exception {
    var start = new CountDownLatch(1);
    var issues = new ArrayList<CompletableFuture<IssuedToken>>();
    try (var threads = Executors.newFixedThreadPool(8)) {
      for (var i = 0; i < 8; i++) {
        issues.add(
            CompletableFuture.supplyAsync(
                () -> {
                  await(start);
                  return enrollments.issue(vm);
                },
                threads));
      }
      start.countDown();
      CompletableFuture.allOf(issues.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
    }
    assertThat(lockedToken()).isPresent();
  }

  @Test
  void aLockedTokenHoldsOffOtherWritersUntilTheTransactionEnds() throws Exception {
    store.saveToken(new EnrollmentToken(vm, new byte[] {1}, EXPIRES));
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holder =
        CompletableFuture.runAsync(
            () ->
                transactions.executeWithoutResult(
                    _ -> {
                      store.lockToken(vm).orElseThrow();
                      locked.countDown();
                      await(release);
                    }));
    await(locked);
    var writer = CompletableFuture.runAsync(() -> store.deleteToken(vm));
    Thread.sleep(Duration.ofMillis(300));
    assertThat(writer).isNotDone();

    release.countDown();
    holder.get(10, TimeUnit.SECONDS);
    writer.get(10, TimeUnit.SECONDS);
    assertThat(lockedToken()).isEmpty();
  }

  @Test
  void savingAnAgentRefusesASecondOneAndKeepsTheFirst() {
    assertThat(store.isEnrolled(vm)).isFalse();
    assertThat(store.saveAgent(vm, new byte[] {1}, EXPIRES)).isTrue();
    assertThat(store.saveAgent(vm, new byte[] {2}, EXPIRES)).isFalse();
    assertThat(store.isEnrolled(vm)).isTrue();
    assertThat(store.publicKey(vm)).hasValueSatisfying(key -> assertThat(key).containsExactly(1));
    assertThat(store.publicKey(UUID.randomUUID())).isEmpty();
  }

  @Test
  void touchingRecordsWhenTheAgentWasSeenAndIgnoresAnUnknownVm() {
    store.saveAgent(vm, new byte[] {1}, EXPIRES);
    store.touch(vm, EXPIRES.plusSeconds(5));
    var other = UUID.randomUUID();
    store.touch(other, EXPIRES);

    assertThat(agents.findById(vm).map(AgentEntity::getLastSeenAt))
        .hasValue(EXPIRES.plusSeconds(5));
    assertThat(store.isEnrolled(other)).isFalse();
  }

  @Test
  void deletingForgetsTheAgentAndItsTokenAndToleratesNothingToForget() {
    store.saveToken(new EnrollmentToken(vm, new byte[] {1}, EXPIRES));
    store.saveAgent(vm, new byte[] {1}, EXPIRES);

    store.delete(vm);
    store.delete(vm);

    assertThat(store.isEnrolled(vm)).isFalse();
    assertThat(lockedToken()).isEmpty();
  }

  private Optional<EnrollmentToken> lockedToken() {
    return transactions.execute(_ -> store.lockToken(vm));
  }

  private Instant createdAt() {
    return tokens.findById(vm).map(EnrollmentTokenEntity::getCreatedAt).orElseThrow();
  }

  // The entity offers no way to set the age, which only the store's own writes may move.
  private void backdate(Instant createdAt) {
    var criteria = entities.getCriteriaBuilder();
    var update = criteria.createCriteriaUpdate(EnrollmentTokenEntity.class);
    var row = update.from(EnrollmentTokenEntity.class);
    update.set(row.<Instant>get("createdAt"), createdAt).where(criteria.equal(row.get("vmId"), vm));
    entities.createQuery(update).executeUpdate();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
