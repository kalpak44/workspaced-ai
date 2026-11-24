package io.github.kalpak44.workspacedai.api.funnel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.Attribution;
import io.github.kalpak44.workspacedai.api.auth.AuthService;
import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.TourProgress;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserEntity_;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.billing.BillingFixtures;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** Sign-ups in a window, counted at each step to paying, per source, and through the tour. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class FunnelTest {

  // A window no other test's accounts fall in, since every account is made "now".
  private static final LocalDate DAY = LocalDate.parse("2020-03-10");
  private static final Instant NOW = Instant.parse("2020-05-01T00:00:00Z");

  @LocalServerPort int port;
  @Autowired FunnelService funnel;
  @Autowired AuthService auth;
  @Autowired UserRepository users;
  @Autowired EntityManager entityManager;
  @Autowired TransactionTemplate transaction;

  @BeforeEach
  void clear() {
    transaction.executeWithoutResult(
        _ -> {
          var cb = entityManager.getCriteriaBuilder();
          var delete = cb.createCriteriaDelete(UserEntity.class);
          var user = delete.from(UserEntity.class);
          delete.where(
              cb.lessThan(user.get(UserEntity_.createdAt), Instant.parse("2021-01-01T00:00:00Z")));
          entityManager.createQuery(delete).executeUpdate();
        });
  }

  @Test
  void countsEachStepPerSourceAndLeavesAdminsOut() {
    var paying = account(new Attribution("newsletter", "nov"), Role.USER);
    var demoOnly = account(new Attribution("newsletter", "nov"), Role.USER);
    account(Attribution.NONE, Role.USER);
    account(new Attribution("newsletter", "nov"), Role.ADMIN);
    users.claimDemo(paying, Instant.now());
    users.claimDemo(demoOnly, Instant.now());
    transaction.executeWithoutResult(
        _ -> {
          BillingFixtures.paidTopUp(entityManager, paying);
          BillingFixtures.usageCharge(entityManager, paying, Instant.parse("2020-04-20T00:00:00Z"));
        });

    var result = funnel.funnel(DAY, DAY, NOW);

    assertThat(result.total()).isEqualTo(new Funnel.Counts(3, 2, 1, 3, 1));
    assertThat(result.sources())
        .containsExactly(
            new Funnel.Source("newsletter", "nov", new Funnel.Counts(2, 2, 1, 2, 1)),
            new Funnel.Source(null, null, new Funnel.Counts(1, 0, 0, 1, 0)));
  }

  @Test
  void showsHowFarTheTourGotAndWhereItStopped() {
    var finished = account(Attribution.NONE, Role.USER);
    var paused = account(Attribution.NONE, Role.USER);
    tour(finished, 0, 1, 2);
    auth.saveTour(finished, null);
    tour(paused, 0, 1);
    auth.saveTour(paused, new TourProgress(1, null, null, null, null, true));

    assertThat(funnel.funnel(DAY, DAY, NOW).tour())
        .containsExactly(
            new Funnel.TourStep(0, 2, 0, 0, 0),
            new Funnel.TourStep(1, 2, 0, 1, 0),
            new Funnel.TourStep(2, 1, 0, 0, 1));
  }

  @Test
  void isForAdminsOnly() throws Exception {
    var user = users.save(TestUsers.newUser("funnel-" + UUID.randomUUID()));
    assertThat(get("/admin/funnel", TestUsers.bearer(user))).isEqualTo(403);
    assertThat(get("/admin/funnel", TestUsers.bearer(port))).isEqualTo(200);
    assertThat(get("/admin/funnel?from=2026-02-01&to=2026-01-01", TestUsers.bearer(port)))
        .isEqualTo(400);
  }

  private UUID account(Attribution attribution, Role role) {
    var id =
        users
            .save(
                new UserEntity(TestUsers.subject(UUID.randomUUID() + "@x.test"), role, attribution))
            .getId();
    // The database stamps created_at, so placing the account in the window takes an update.
    transaction.executeWithoutResult(
        _ -> {
          var cb = entityManager.getCriteriaBuilder();
          var update = cb.createCriteriaUpdate(UserEntity.class);
          var user = update.from(UserEntity.class);
          update
              .set(user.get(UserEntity_.createdAt), Instant.parse("2020-03-10T12:00:00Z"))
              .where(cb.equal(user.get(UserEntity_.id), id));
          entityManager.createQuery(update).executeUpdate();
        });
    return id;
  }

  private void tour(UUID user, int... steps) {
    for (var step : steps) {
      auth.saveTour(user, new TourProgress(step, null, null, null, null, false));
    }
  }

  private int get(String path, String bearer) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", bearer)
            .build();
    return HttpClient.newHttpClient().send(request, BodyHandlers.discarding()).statusCode();
  }
}
