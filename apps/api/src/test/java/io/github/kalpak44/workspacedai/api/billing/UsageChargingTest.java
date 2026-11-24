package io.github.kalpak44.workspacedai.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.compute.VmSize;
import io.github.kalpak44.workspacedai.api.mail.Mailer;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Mail;
import io.github.kalpak44.workspacedai.api.notification.Notification;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.NotificationTone;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A user who is not an admin pays for a workspace from the balance: a month of it and of every
 * workspace they already have up front to create one, then by the hour, until the balance runs out
 * and the workspace goes.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class UsageChargingTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  // 6.59 a month costs a user 8.90 with the 35% markup, which is 8.90 / 730 an hour.
  private static final VmSize CX23 =
      new VmSize(
          "cx23",
          true,
          2,
          4.0,
          40,
          "shared",
          "x86",
          "nbg1",
          new BigDecimal("5.49"),
          new BigDecimal("6.59"),
          true,
          true);
  private static final long HOURLY = 12_192;
  private static final long MONTH = 8_900_000;
  private static final long FEE = 5_000_000;

  @LocalServerPort int port;
  @MockitoBean VmManager vmManager;
  @MockitoBean ApiKeyCheck keyCheck;
  @MockitoBean Mailer mailer;
  @Autowired UserRepository users;
  @Autowired WorkspaceRepository workspaces;
  @Autowired UsageRepository usage;
  @Autowired BillingAccountRepository accounts;
  @Autowired Ledger ledger;
  @Autowired TransactionTemplate transactions;
  @Autowired UsageCharger charger;
  @Autowired FundsReminder reminder;
  @Autowired NotificationService notifications;
  @Autowired WorkspaceRuntime runtime;

  private final HttpClient http = HttpClient.newHttpClient();
  private UUID user;
  private String email;
  private String bearer;

  @BeforeEach
  void signIn() {
    when(vmManager.sizes()).thenReturn(List.of(CX23));
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    var name = "payer-" + UUID.randomUUID();
    email = name + "@example.com";
    user = users.save(TestUsers.newUser(email)).getId();
    bearer = TestUsers.bearer(users.findByIdpSubject(TestUsers.subject(email)).orElseThrow());
  }

  @Test
  void refusesWithoutAMonthOfBalanceAndSaysHowMuch() throws Exception {
    fund(MONTH - 1);

    var refused = create(bearer);

    assertThat(refused.statusCode()).isEqualTo(402);
    assertThat(refused.body()).contains("costs 8.90 EUR a month").contains("add funds");
    assertThat(workspaces.findAllByOwnerIdOrderByCreatedAtAscIdAsc(user)).isEmpty();
  }

  @Test
  void refusesUnlessTheBalanceCoversAMonthOfEveryWorkspaceWithTheNewOne() throws Exception {
    fund(2 * MONTH - 1);
    created(create(bearer));

    var refused = create(bearer);

    assertThat(refused.statusCode()).isEqualTo(402);
    assertThat(refused.body())
        .contains("Your workspaces cost 8.90 EUR a month and this size adds 8.90 EUR");
    assertThat(workspaces.findAllByOwnerIdOrderByCreatedAtAscIdAsc(user)).hasSize(1);
  }

  @Test
  void reportsWhatTheWorkspacesCostAndUntilWhenTheBalancePaysForThem() throws Exception {
    fund(MONTH);
    var workspace = created(create(bearer));
    var row = usage.findById(workspace).orElseThrow();
    charger.charge(workspace, row.getChargedUntil().plusSeconds(3600));
    var now = Instant.now();

    var billing = JSON.readTree(billing().body());

    assertThat(billing.get("monthlySpendCents").asLong()).isEqualTo(890);
    assertThat(billing.get("workspaces"))
        .singleElement()
        .satisfies(
            spend -> {
              assertThat(spend.get("id").asString()).isEqualTo(workspace.toString());
              assertThat(spend.get("monthlyCents").asLong()).isEqualTo(890);
            });
    // Two hours are paid, which leaves 727 whole hours of the balance.
    var fundedUntil = Instant.parse(billing.get("fundedUntil").asString());
    assertThat(fundedUntil)
        .isBetween(now.plus(Duration.ofHours(726)), now.plus(Duration.ofHours(727)));
    assertThat(billing.get("usage"))
        .singleElement()
        .satisfies(day -> assertThat(day.get("amountCents").asLong()).isEqualTo(2));
  }

  @Test
  void reportsNoSpendingWithoutWorkspaces() throws Exception {
    fund(MONTH);

    var billing = JSON.readTree(billing().body());

    assertThat(billing.get("monthlySpendCents").asLong()).isZero();
    assertThat(billing.get("fundedUntil").isNull()).isTrue();
    assertThat(billing.get("workspaces")).isEmpty();
    assertThat(billing.get("usage")).isEmpty();
  }

  @Test
  void chargesEachStartedHourAndNothingTwice() throws Exception {
    fund(MONTH);
    var workspace = created(create(bearer));
    var row = usage.findById(workspace).orElseThrow();
    assertThat(row.getHourlyMicros()).isEqualTo(HOURLY);

    var now = row.getChargedUntil().plusSeconds(90 * 60);
    charger.charge(workspace, now);
    charger.charge(workspace, now);

    assertThat(balance()).isEqualTo(MONTH - 2 * HOURLY);
    assertThat(usage.findById(workspace).orElseThrow().getChargedUntil())
        .isEqualTo(row.getChargedUntil().plusSeconds(2 * 3600));
    assertThat(workspaces.findById(workspace)).isPresent();
  }

  @Test
  void pausesTheWorkspaceOnceTheBalanceCannotPayTheNextHourAndStopsCharging() throws Exception {
    var workspace = paused();

    assertThat(balance()).isEqualTo(HOURLY / 2);
    assertThat(workspaces.findById(workspace)).isPresent();
    assertThat(usage.findById(workspace).orElseThrow().getPausedAt()).isNotNull();
    assertThat(runtime.statuses(Set.of(workspace)))
        .containsEntry(workspace, WorkspaceStatus.PAUSED);
    var notice = notifications.list(user).getFirst();
    assertThat(notice.title()).endsWith("is paused");
    assertThat(notice.tone()).isEqualTo(NotificationTone.ERROR);
    assertThat(notice.body()).contains("5.00 EUR reactivation fee");
    assertThat(mails())
        .singleElement()
        .satisfies(
            mail -> {
              assertThat(mail.to()).extracting(Mailer.Address::email).containsExactly(email);
              assertThat(mail.subject()).isEqualTo(notice.title());
              assertThat(mail.text()).contains("5.00 EUR reactivation fee", "[Add funds](");
              assertThat(mail.html()).contains("cid:logo");
            });

    charger.charge(workspace, Instant.now().plus(Duration.ofHours(5)));
    assertThat(balance()).isEqualTo(HOURLY / 2);
  }

  @Test
  void deletesAPausedWorkspaceOnceItHasBeenPausedForThreeDays() throws Exception {
    var workspace = paused();
    var pausedAt = usage.findById(workspace).orElseThrow().getPausedAt();
    var name = workspaces.findById(workspace).orElseThrow().getName();

    charger.settlePaused(workspace, pausedAt.plus(Duration.ofDays(3)).minusSeconds(1));
    assertThat(workspaces.findById(workspace)).isPresent();

    charger.settlePaused(workspace, pausedAt.plus(Duration.ofDays(3)));
    assertThat(workspaces.findById(workspace)).isEmpty();
    assertThat(usage.findById(workspace)).isEmpty();
    assertThat(notifications.list(user).getFirst())
        .extracting(Notification::title, Notification::summary)
        .containsExactly("Workspace deleted", name + " was deleted: the balance ran out.");
    assertThat(mails()).extracting(Mail::subject).endsWith("Workspace deleted");
  }

  @Test
  void startsAPausedWorkspaceAgainOnceTheBalancePaysTheFeeAndAnHour() throws Exception {
    var workspace = paused();
    fund(FEE + HOURLY / 2 - 1);

    charger.settlePaused(workspace, Instant.now());
    assertThat(usage.findById(workspace).orElseThrow().getPausedAt()).isNotNull();

    fund(1);
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    charger.settlePaused(workspace, now);

    var row = usage.findById(workspace).orElseThrow();
    assertThat(row.getPausedAt()).isNull();
    assertThat(row.getChargedUntil()).isEqualTo(now);
    assertThat(balance()).isEqualTo(HOURLY);
    assertThat(runtime.statuses(Set.of(workspace))).containsEntry(workspace, WorkspaceStatus.READY);
    assertThat(notifications.list(user).getFirst().title()).endsWith("is running again");
    assertThat(mails()).extracting(Mail::subject).noneMatch(s -> s.endsWith("running again"));
  }

  @Test
  void showsAPausedWorkspaceWithWhenItIsDeletedAndCountsItsHoursNoMore() throws Exception {
    var workspace = paused();

    var billing = JSON.readTree(billing().body());

    assertThat(billing.get("reactivationFeeCents").asLong()).isEqualTo(500);
    assertThat(billing.get("pausedForHours").asLong()).isEqualTo(72);
    assertThat(billing.get("monthlySpendCents").asLong()).isZero();
    assertThat(billing.get("fundedUntil").isNull()).isTrue();
    var deletesAt = Instant.parse(billing.get("workspaces").get(0).get("deletesAt").asString());
    assertThat(deletesAt)
        .isEqualTo(usage.findById(workspace).orElseThrow().getPausedAt().plus(Duration.ofDays(3)));
  }

  @Test
  void neverChargesAUserMadeAnAdmin() throws Exception {
    fund(MONTH);
    var workspace = created(create(bearer));
    var entity = users.findById(user).orElseThrow();
    // As the next sign-in does once Auth0 assigns the role.
    entity.signedIn(Role.ADMIN);
    users.save(entity);
    var before = balance();

    var now = Instant.now().plus(Duration.ofHours(3));
    charger.charge(workspace, now);

    assertThat(balance()).isEqualTo(before);
    assertThat(usage.findById(workspace).orElseThrow().getChargedUntil()).isAfter(now);
  }

  @Test
  void remindsOnceADayOfEachWorkspaceTheBalanceStopsPayingForWithinAWeek() throws Exception {
    fund(MONTH);
    var workspace = created(create(bearer));
    var now = Instant.now();
    reminder.remind(now);
    assertThat(reminders()).isEmpty();

    // Leaves three and a half days of the workspace's hours.
    fund(-(MONTH - HOURLY * 84));
    reminder.remind(now);
    reminder.remind(now);
    assertThat(reminders())
        .singleElement()
        .extracting(Notification::title)
        .asString()
        .endsWith("will be paused in about 3 days");
    assertThat(reminders().getFirst().summary())
        .contains("costs a 5.00 EUR fee")
        .endsWith("deleted about 3 days later.");

    assertThat(mails())
        .singleElement()
        .extracting(Mail::subject)
        .asString()
        .endsWith("will be paused in about 3 days");

    reminder.remind(now.plus(Duration.ofDays(1)));
    assertThat(reminders()).hasSize(2);
    assertThat(workspaces.findById(workspace)).isPresent();
  }

  @Test
  void mailsTheBalanceReminderAtAWeekThreeDaysAndOneDayLeftOnly() throws Exception {
    fund(MONTH);
    created(create(bearer));
    var now = Instant.now();

    fund(-(MONTH - HOURLY * 84));
    reminder.remind(now);
    reminder.remind(now.plus(Duration.ofDays(1)));
    assertThat(reminders()).hasSize(2);
    assertThat(mails()).singleElement().extracting(Mail::subject).asString().endsWith("3 days");

    // Crossing a milestone during the day is told of at once, in the app and by mail.
    fund(-HOURLY * 24);
    reminder.remind(now.plus(Duration.ofDays(1)));
    fund(-HOURLY * 48);
    reminder.remind(now.plus(Duration.ofDays(1)));
    reminder.remind(now.plus(Duration.ofDays(2)));

    assertThat(reminders()).hasSize(5);
    assertThat(mails())
        .extracting(Mail::subject)
        .hasSize(3)
        .last()
        .asString()
        .endsWith("will be paused within a day");
  }

  @Test
  void remindsOnceADayOfAPausedWorkspaceUntilItIsDeleted() throws Exception {
    var workspace = paused();
    var now = Instant.now().truncatedTo(ChronoUnit.DAYS);
    reminder.remind(now);
    assertThat(deletionReminders()).isEmpty();

    transactions.executeWithoutResult(
        _ -> usage.findById(workspace).orElseThrow().pause(now.minus(Duration.ofDays(1))));
    reminder.remind(now);
    reminder.remind(now);

    assertThat(deletionReminders())
        .singleElement()
        .extracting(Notification::title)
        .asString()
        .endsWith("will be deleted in about 2 days");
    assertThat(deletionMails()).isEmpty();

    // Mailed on its last day only, once.
    transactions.executeWithoutResult(
        _ ->
            usage
                .findById(workspace)
                .orElseThrow()
                .pause(now.minus(Duration.ofDays(2)).minus(Duration.ofHours(12))));
    reminder.remind(now);
    reminder.remind(now.plus(Duration.ofHours(6)));

    assertThat(deletionReminders()).hasSize(2);
    assertThat(deletionMails()).singleElement().asString().endsWith("will be deleted within a day");
  }

  private List<String> deletionMails() {
    return mails().stream().map(Mail::subject).filter(s -> s.contains("will be deleted")).toList();
  }

  @Test
  void neverChargesAnAdmin() throws Exception {
    var workspace = created(create(TestUsers.bearer(port)));

    assertThat(usage.findById(workspace)).isEmpty();
  }

  /** A workspace that paid its first hour, then could not pay its second. */
  private UUID paused() throws Exception {
    fund(MONTH);
    var workspace = created(create(bearer));
    var row = usage.findById(workspace).orElseThrow();
    // Leaves half an hour's worth, short of the second hour.
    fund(-(balance() - HOURLY - HOURLY / 2));
    charger.charge(workspace, row.getChargedUntil().plusSeconds(3600));
    return workspace;
  }

  private List<Notification> deletionReminders() {
    return notifications.list(user).stream()
        .filter(n -> n.title().contains("will be deleted"))
        .toList();
  }

  private List<Notification> reminders() {
    return notifications.list(user).stream()
        .filter(n -> n.title().contains("will be paused"))
        .toList();
  }

  // The mail this test's user was sent; a reminder also reaches users earlier tests left behind.
  private List<Mail> mails() {
    var mail = ArgumentCaptor.forClass(Mail.class);
    verify(mailer, atLeast(0)).send(mail.capture());
    return mail.getAllValues().stream()
        .filter(m -> m.to().stream().anyMatch(to -> to.email().equals(email)))
        .toList();
  }

  private void fund(long micros) {
    transactions.executeWithoutResult(
        _ -> ledger.post(user, micros, Ledger.TOP_UP, "test-" + UUID.randomUUID()));
  }

  private long balance() {
    return accounts.findById(user).orElseThrow().getBalanceMicros();
  }

  private UUID created(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private HttpResponse<String> billing() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/billing"))
            .header("Authorization", bearer)
            .GET()
            .build();
    return http.send(request, BodyHandlers.ofString());
  }

  private HttpResponse<String> create(String authorization) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/workspaces"))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .POST(
                BodyPublishers.ofString(
                    "{\"name\":\"Paid "
                        + Instant.now().toEpochMilli()
                        + "\",\"modelProvider\":\"openrouter\",\"apiKey\":\"sk-or-1\"}"))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
