package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterKeyMetrics;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterMetrics;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionEntity;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class WorkspaceAiCreditsTest {
  @LocalServerPort int port;
  @Autowired WorkspaceRepository workspaces;
  @Autowired WorkspaceSessionRepository sessions;
  @Autowired UserRepository users;
  @Autowired AiConnectorRepository connectors;
  @Autowired AuthProperties properties;
  @Autowired WorkspaceAiCredits credits;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean OpenRouterMetrics metrics;
  @MockitoBean Harness harness;
  UserEntity owner;
  UserEntity member;
  UUID workspace;
  UUID connector;
  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void prepare() {
    workspaces.deleteAll();
    owner = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com"));
    member = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com"));
    connector =
        connectors
            .saveAndFlush(
                new AiConnectorEntity(
                    owner.getId(),
                    "Private name",
                    "openrouter",
                    new SecretBox(properties.jwtSecret(), "workspaced-ai-connectors")
                        .seal("private-key")))
            .getId();
    var row = new WorkspaceEntity(owner.getId(), "Work", "openrouter", "UTC");
    row.setAiConnectorId(connector);
    workspace = workspaces.saveAndFlush(row).getId();
    jdbc.update(
        "insert into workspace_collaborator(workspace_id,user_id,invited_by,joined_at,name) values (?,?,?,now(),?)",
        workspace,
        member.getId(),
        owner.getId(),
        "Work");
    remaining("0.50");
  }

  void remaining(String amount) {
    when(metrics.read("private-key"))
        .thenReturn(
            new OpenRouterKeyMetrics(
                null,
                null,
                null,
                null,
                new BigDecimal("10"),
                amount == null ? null : new BigDecimal(amount),
                null,
                null,
                null,
                null,
                null,
                null,
                null));
  }

  @Test
  void sharesOnlyCreditStatusAndUsesEachViewersSavedThreshold() throws Exception {
    var path = "/workspaces/" + workspace + "/ai-credits";
    var response = send(member, "GET", path, null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(response.body())
        .contains("\"low\":true")
        .doesNotContain("private-key", "Private name", connector.toString());
    var stranger = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com"));
    assertThat(send(stranger, "GET", path, null).statusCode()).isEqualTo(404);
    assertThat(send(member, "GET", "/ai-connectors/" + connector + "/metrics", null).statusCode())
        .isEqualTo(404);
    var saved =
        send(member, "PUT", "/account/ai-credit-preferences", "{\"warningThresholdUsd\":0.50}");
    assertThat(saved.statusCode()).isEqualTo(200);
    assertThat(json.readTree(saved.body()).get("aiCreditWarningUsd").decimalValue())
        .isEqualByComparingTo("0.50");
    assertThat(send(member, "GET", path, null).body()).contains("\"low\":false");
    assertThat(send(owner, "GET", path, null).body()).contains("\"low\":true");
    assertThat(
            send(member, "PUT", "/account/ai-credit-preferences", "{\"warningThresholdUsd\":-1}")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send(member, "PUT", "/account/ai-credit-preferences", "{\"warningThresholdUsd\":1.001}")
                .statusCode())
        .isEqualTo(400);
    assertThat(send(member, "PUT", "/account/ai-credit-preferences", "{}").statusCode())
        .isEqualTo(400);
  }

  @Test
  void blocksOwnerAndCollaboratorMessagesAndEditsAtZeroIncludingTrial() throws Exception {
    remaining("0");
    for (var user : new UserEntity[] {owner, member}) {
      var id = UUID.randomUUID();
      sessions.saveAndFlush(
          new WorkspaceSessionEntity(id, workspace, user.getId(), "/work/" + id, "remote-" + id));
      var path = "/workspaces/" + workspace + "/sessions/" + id;
      assertThat(send(user, "POST", path + "/messages", "{\"text\":\"Hello\"}").statusCode())
          .isEqualTo(402);
      assertThat(send(user, "PUT", path + "/messages/msg-1", "{\"text\":\"Again\"}").statusCode())
          .isEqualTo(402);
    }
    verify(harness, never()).prompt(any(), any(), any(), any(), any());
    jdbc.update("update ai_connector set trial=true where id=?", connector);
    var status = credits.status(workspace, owner.getId());
    assertThat(status.trial()).isTrue();
    assertThat(status.low()).isTrue();
    assertThat(status.exhausted()).isTrue();
    var id = UUID.randomUUID();
    sessions.saveAndFlush(
        new WorkspaceSessionEntity(id, workspace, owner.getId(), "/work/" + id, "remote-" + id));
    assertThat(
            send(
                    owner,
                    "POST",
                    "/workspaces/" + workspace + "/sessions/" + id + "/messages",
                    "{\"text\":\"Hello\"}")
                .statusCode())
        .isEqualTo(402);
    remaining("0.01");
    assertThatCode(() -> credits.requireCredits(workspace)).doesNotThrowAnyException();
  }

  @Test
  void warnsForTrialTokensBelowTwentyCentsInEveryWorkspaceRegardlessOfUserThreshold() {
    jdbc.update("update ai_connector set trial=true where id=?", connector);
    jdbc.update("update app_user set ai_credit_warning_usd=0 where id=?", member.getId());
    for (var demo : new boolean[] {false, true}) {
      jdbc.update("update workspace set demo=? where id=?", demo, workspace);
      remaining("0.20");
      assertThat(credits.status(workspace, member.getId()).low()).isFalse();
      remaining("0.19");
      var status = credits.status(workspace, member.getId());
      assertThat(status.warningThresholdUsd()).isEqualByComparingTo("0.20");
      assertThat(status.low()).isTrue();
      assertThat(status.trial()).isTrue();
      assertThat(status.exhausted()).isFalse();
      remaining("0");
      assertThat(credits.status(workspace, member.getId()).exhausted()).isTrue();
    }
  }

  @Test
  void hidesDemoWarningsAndDoesNotInventCreditForUnknownBalances() {
    jdbc.update("update workspace set demo=true where id=?", workspace);
    assertThat(credits.status(workspace, owner.getId()).low()).isFalse();
    remaining(null);
    assertThat(credits.status(workspace, owner.getId()).remainingUsd()).isNull();
    assertThat(credits.status(workspace, owner.getId()).exhausted()).isFalse();
  }

  HttpResponse<String> send(UserEntity user, String method, String path, String body)
      throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", TestUsers.bearer(user))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
