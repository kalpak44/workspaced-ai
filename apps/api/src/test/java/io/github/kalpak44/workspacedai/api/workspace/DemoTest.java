package io.github.kalpak44.workspacedai.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.billing.BillingFixtures;
import io.github.kalpak44.workspacedai.api.compute.VmPricing;
import io.github.kalpak44.workspacedai.api.compute.VmSize;
import io.github.kalpak44.workspacedai.api.mail.Mailer;
import io.github.kalpak44.workspacedai.api.notification.Notification;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import jakarta.persistence.EntityManager;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every account gets one free demo workspace: started with no balance, never charged, and deleted
 * once its hours are up, with a warning shortly before.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "workspaced.demo.at-once=2")
@Import({TestDatabase.class, TestRuntime.class})
class DemoTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @MockitoBean ApiKeyCheck keyCheck;
  @MockitoBean VmPricing pricing;
  @MockitoBean Mailer mailer;
  @MockitoSpyBean WorkspaceRuntime runtime;
  @Autowired UserRepository users;
  @Autowired WorkspaceRepository workspaces;
  @Autowired DemoReaper reaper;
  @Autowired NotificationService notifications;
  @Autowired EntityManager entityManager;
  @Autowired ApplicationContext context;
  @Autowired WorkspaceService service;

  private final HttpClient http = HttpClient.newHttpClient();
  private UUID user;
  private String bearer;

  @BeforeEach
  void signIn() {
    workspaces.deleteAll();
    when(pricing.sizes(true))
        .thenReturn(
            List.of(
                size("cx53", true, true, "20"),
                size("cx23", false, true, "5"),
                size("sold-out", false, false, "1")));
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    var name = "visitor-" + UUID.randomUUID();
    var saved = users.save(TestUsers.newUser(name));
    user = saved.getId();
    bearer = TestUsers.bearer(saved);
  }

  @Test
  void startsAFreeDemoWithAnEmptyBalanceForAFewHours() throws Exception {
    assertThat(account().get("demoAvailable").asBoolean()).isTrue();

    var created = send("POST", "/workspaces", demo("sk-or-1"));

    assertThat(created.statusCode()).isEqualTo(201);
    var workspace = JSON.readValue(created.body(), Workspace.class);
    assertThat(workspace.demo()).isTrue();
    assertThat(workspace.expiresAt())
        .isCloseTo(Instant.now().plus(Duration.ofHours(4)), within(1, ChronoUnit.MINUTES));
    // A size asked for is ignored: pick the cheapest available, even if it is not the default.
    verify(runtime).start(eq(workspace.id()), eq(user), any(), eq("cx23"), any());
    assertThat(BillingFixtures.usageRows(entityManager, workspace.id())).isZero();
    assertThat(account().get("demoAvailable").asBoolean()).isFalse();
    assertThat(newest().title()).isEqualTo("Your demo workspace is starting");
  }

  @Test
  void notifiesCompletionOnceWithTheDeletionTimeWithoutEmail() throws Exception {
    var workspace =
        JSON.readValue(send("POST", "/workspaces", demo("sk-or-1")).body(), Workspace.class);
    var path = "/workspaces/" + workspace.id() + "/tutorial-completed";
    assertThat(send("POST", path, null).statusCode()).isEqualTo(204);
    assertThat(send("POST", path, null).statusCode()).isEqualTo(204);
    var completed =
        notifications.list(user).stream()
            .filter(n -> n.title().equals("Tutorial completed"))
            .toList();
    assertThat(completed).hasSize(1);
    assertThat(completed.getFirst().body())
        .contains("permanently deleted", "secrets", "scheduled tasks", "not for production", "UTC");
    assertThat(completed.getFirst().action().path()).isEqualTo("/workspaces/" + workspace.id());
    verifyNoInteractions(mailer);
  }

  @Test
  void announcesDeletionOnlyAfterTheRuntimeIsGone() throws Exception {
    var workspace =
        JSON.readValue(send("POST", "/workspaces", demo("sk-or-1")).body(), Workspace.class);
    var now = workspace.expiresAt().plusSeconds(1);
    org.mockito.Mockito.doThrow(new IllegalStateException("VM unavailable"))
        .doNothing()
        .when(runtime)
        .stop(workspace.id());
    reaper.reap(now);
    assertThat(workspaces.existsById(workspace.id())).isTrue();
    assertThat(notifications.list(user)).noneMatch(n -> n.title().equals("Your demo has ended"));
    reaper.reap(now);
    reaper.reap(now.plusSeconds(60));
    assertThat(workspaces.existsById(workspace.id())).isFalse();
    assertThat(notifications.list(user))
        .filteredOn(n -> n.title().equals("Your demo has ended"))
        .hasSize(1);
    verifyNoInteractions(mailer);
  }

  @Test
  void givesNoSecondDemoEvenOnceTheFirstIsDeleted() throws Exception {
    var first =
        JSON.readValue(send("POST", "/workspaces", demo("sk-or-1")).body(), Workspace.class);
    assertThat(send("DELETE", "/workspaces/" + first.id(), null).statusCode()).isEqualTo(204);

    var again = send("POST", "/workspaces", demo("sk-or-1"));

    assertThat(again.statusCode()).isEqualTo(409);
    assertThat(again.body()).contains("this account has had its demo");
  }

  @Test
  void handsTheDemoBackWhenItsVmFailsToStart() throws Exception {
    doThrow(new IllegalStateException("Hetzner is down"))
        .when(runtime)
        .start(any(), eq(user), any(), any(), any());

    assertThat(send("POST", "/workspaces", demo("sk-or-1")).statusCode()).isEqualTo(500);

    assertThat(workspaces.findAllByOwnerIdOrderByCreatedAtAscIdAsc(user)).isEmpty();
    assertThat(account().get("demoAvailable").asBoolean()).isTrue();
  }

  @Test
  void launchesADemoDraftOnceItsKeyArrives() throws Exception {
    var draft = JSON.readValue(send("POST", "/workspaces", demo(null)).body(), Workspace.class);
    assertThat(draft.status()).isEqualTo(WorkspaceStatus.DRAFT);
    // The draft does not use the demo up: a closed dialog would otherwise cost it.
    assertThat(account().get("demoAvailable").asBoolean()).isTrue();

    // Capacity changes while the owner connects OpenRouter: select again at launch.
    when(pricing.sizes(true)).thenReturn(List.of(size("cx33", false, true, "8")));
    var launched = send("POST", "/workspaces/" + draft.id() + "/launch", "{\"apiKey\":\"sk-2\"}");

    assertThat(launched.statusCode()).isEqualTo(200);
    var workspace = JSON.readValue(launched.body(), Workspace.class);
    assertThat(workspace.demo()).isTrue();
    assertThat(workspace.expiresAt()).isNotNull();
    verify(runtime).start(eq(workspace.id()), eq(user), any(), eq("cx33"), any());
    assertThat(account().get("demoAvailable").asBoolean()).isFalse();
  }

  @Test
  void keepsOneDemoDraftWhenTheTourStartsAgainElsewhere() throws Exception {
    var first = JSON.readValue(send("POST", "/workspaces", demo(null)).body(), Workspace.class);

    var again = JSON.readValue(send("POST", "/workspaces", demo(null)).body(), Workspace.class);

    assertThat(again.id()).isEqualTo(first.id());
    assertThat(workspaces.findAllByOwnerIdOrderByCreatedAtAscIdAsc(user)).hasSize(1);
    assertThat(
            send("POST", "/workspaces/" + again.id() + "/launch", "{\"apiKey\":\"sk-2\"}")
                .statusCode())
        .isEqualTo(200);
    assertThat(send("POST", "/workspaces", demo(null)).statusCode()).isEqualTo(409);
  }

  @Test
  void keepsTheTourOnTheAccountSoAnyBrowserResumesIt() throws Exception {
    assertThat(account().get("tour").isNull()).isTrue();
    var progress =
        "{\"step\":3,\"draftId\":\"d-1\",\"name\":\"My first workspace\",\"paused\":true}";

    assertThat(send("PUT", "/account/tour", progress).statusCode()).isEqualTo(204);

    var tour = account().get("tour");
    assertThat(tour.get("step").asInt()).isEqualTo(3);
    assertThat(tour.get("draftId").asString()).isEqualTo("d-1");
    assertThat(tour.get("paused").asBoolean()).isTrue();
    assertThat(send("DELETE", "/account/tour", null).statusCode()).isEqualTo(204);
    assertThat(account().get("tour").isNull()).isTrue();
  }

  @Test
  void refusesTourProgressOutsideItsBounds() throws Exception {
    assertThat(send("PUT", "/account/tour", "{\"step\":101}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", "/account/tour", "{}").statusCode()).isEqualTo(400);
    var longName = "x".repeat(101);
    assertThat(
            send("PUT", "/account/tour", "{\"step\":1,\"name\":\"" + longName + "\"}").statusCode())
        .isEqualTo(400);
    assertThat(account().get("tour").isNull()).isTrue();
  }

  @Test
  void refusesADemoWhileEveryDemoIsTaken() throws Exception {
    for (var i = 0; i < 2; i++) {
      var other = users.save(TestUsers.newUser("other-" + UUID.randomUUID()));
      assertThat(send("POST", "/workspaces", demo("sk-or-1"), TestUsers.bearer(other)).statusCode())
          .isEqualTo(201);
    }

    var refused = send("POST", "/workspaces", demo("sk-or-1"));

    assertThat(refused.statusCode()).isEqualTo(503);
    assertThat(account().get("demoAvailable").asBoolean()).isTrue();
  }

  @Test
  void warnsOnceShortlyBeforeAndThenDeletesIt() throws Exception {
    var id =
        JSON.readValue(send("POST", "/workspaces", demo("sk-or-1")).body(), Workspace.class).id();
    var expiresAt = workspaces.findById(id).orElseThrow().getExpiresAt();

    reaper.reap(expiresAt.minus(Duration.ofHours(1)));
    assertThat(newest().title()).isEqualTo("Your demo workspace is starting");

    reaper.reap(expiresAt.minus(Duration.ofMinutes(20)));
    reaper.reap(expiresAt.minus(Duration.ofMinutes(10)));
    assertThat(notifications.list(user))
        .filteredOn(n -> n.title().endsWith("is deleted in 20 minutes"))
        .hasSize(1);
    assertThat(notifications.list(user)).noneMatch(n -> n.title().endsWith("10 minutes"));
    assertThat(workspaces.existsById(id)).isTrue();
    assertThat(newest().summary()).contains("UTC", "deleted on");

    reaper.reap(expiresAt.plusSeconds(1));
    assertThat(workspaces.existsById(id)).isFalse();
    assertThat(newest().title()).isEqualTo("Your demo has ended");
    verifyNoInteractions(mailer);
  }

  @Test
  void keepsTheDemoOnItsVmAndChargesItsPlanFromThen() throws Exception {
    when(pricing.monthlyPrice("cx23")).thenReturn(Optional.of(new BigDecimal("10.65")));
    var id =
        JSON.readValue(send("POST", "/workspaces", demo("sk-or-1")).body(), Workspace.class).id();
    BillingFixtures.credit(context, user, 1000);

    var kept = send("POST", "/workspaces/" + id + "/keep", null);

    assertThat(kept.statusCode()).isEqualTo(200);
    var workspace = JSON.readValue(kept.body(), Workspace.class);
    assertThat(workspace.demo()).isFalse();
    assertThat(workspace.expiresAt()).isNull();
    assertThat(BillingFixtures.usageRows(entityManager, id)).isOne();
    assertThat(newest().title()).isEqualTo("Demo is yours to keep");
    // Still the same VM: it was neither stopped nor started again.
    verify(runtime, never()).stop(id);
    assertThat(send("POST", "/workspaces/" + id + "/keep", null).statusCode()).isEqualTo(409);
  }

  @Test
  void asksForWhatTheBalanceLacksToKeepIt() throws Exception {
    when(pricing.monthlyPrice("cx23")).thenReturn(Optional.of(new BigDecimal("10.65")));
    var id =
        JSON.readValue(send("POST", "/workspaces", demo("sk-or-1")).body(), Workspace.class).id();

    var refused = send("POST", "/workspaces/" + id + "/keep", null);

    assertThat(refused.statusCode()).isEqualTo(402);
    // A week of €10.65 a month, rounded up to the cent, and the balance is empty.
    assertThat(JSON.readTree(refused.body()).get("shortCents").asLong()).isEqualTo(246);
    assertThat(workspaces.findById(id).orElseThrow().isDemo()).isTrue();
  }

  @Test
  void neitherKeepsNorSparesADemoAtItsEnd() throws Exception {
    when(pricing.monthlyPrice("cx23")).thenReturn(Optional.of(new BigDecimal("10.65")));
    BillingFixtures.credit(context, user, 1000);
    var late =
        JSON.readValue(send("POST", "/workspaces", demo("sk-or-1")).body(), Workspace.class).id();
    var row = workspaces.findById(late).orElseThrow();
    row.setExpiresAt(Instant.now().plusSeconds(30));
    workspaces.save(row);

    assertThat(send("POST", "/workspaces/" + late + "/keep", null).statusCode()).isEqualTo(409);
    reaper.reap(Instant.now().plus(Duration.ofMinutes(1)));
    assertThat(workspaces.existsById(late)).isFalse();
  }

  @Test
  void leavesAKeptDemoToItsOwnerEvenOnceItsTimeHasPassed() throws Exception {
    when(pricing.monthlyPrice("cx23")).thenReturn(Optional.of(new BigDecimal("10.65")));
    BillingFixtures.credit(context, user, 1000);
    var id =
        JSON.readValue(send("POST", "/workspaces", demo("sk-or-1")).body(), Workspace.class).id();
    var expiresAt = workspaces.findById(id).orElseThrow().getExpiresAt();
    assertThat(send("POST", "/workspaces/" + id + "/keep", null).statusCode()).isEqualTo(200);

    // As a reaper that listed it just before the keep would then try to delete it.
    assertThat(service.deleteExpiredDemo(id, expiresAt.plusSeconds(1))).isFalse();
    reaper.reap(expiresAt.plusSeconds(1));
    assertThat(workspaces.existsById(id)).isTrue();
  }

  @Test
  void leavesWorkspacesThatAreNotDemosAlone() throws Exception {
    var admin = TestUsers.bearer(port);
    var created =
        send(
            "POST",
            "/workspaces",
            "{\"name\":\"Kept\",\"modelProvider\":\"openrouter\",\"apiKey\":\"sk-or-1\"}",
            admin);
    var id = JSON.readValue(created.body(), Workspace.class).id();

    reaper.reap(Instant.now().plus(Duration.ofDays(365)));

    assertThat(workspaces.existsById(id)).isTrue();
  }

  @Test
  void refusesWhenNoMachineIsAvailableWithoutUsingTheTrial() throws Exception {
    when(pricing.sizes(true)).thenReturn(List.of(size("cx23", true, false, "5")));
    assertThat(send("POST", "/workspaces", demo("sk-or-1")).statusCode()).isEqualTo(503);
    assertThat(account().get("demoAvailable").asBoolean()).isTrue();
    assertThat(workspaces.findAllByOwnerIdOrderByCreatedAtAscIdAsc(user)).isEmpty();
  }

  private static VmSize size(String type, boolean isDefault, boolean available, String price) {
    return new VmSize(
        null,
        type,
        isDefault,
        2,
        4,
        40,
        "shared",
        "x86",
        "nbg1",
        new BigDecimal(price),
        new BigDecimal(price),
        true,
        available);
  }

  private Notification newest() {
    return notifications.list(user).getFirst();
  }

  private tools.jackson.databind.JsonNode account() throws Exception {
    return JSON.readTree(send("GET", "/account", null).body());
  }

  private static String demo(String apiKey) {
    var key = apiKey == null ? "" : ",\"apiKey\":\"" + apiKey + "\"";
    return "{\"name\":\"Demo\",\"modelProvider\":\"openrouter\",\"serverType\":\"cx53\","
        + "\"demo\":true"
        + key
        + "}";
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    return send(method, path, body, bearer);
  }

  private HttpResponse<String> send(String method, String path, String body, String authorization)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
