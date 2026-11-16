package io.github.kalpak44.workspacedai.api.firewall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.compute.FirewallRule;
import io.github.kalpak44.workspacedai.api.compute.FirewallRuleRejectedException;
import io.github.kalpak44.workspacedai.api.compute.VmFirewall;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * The VM manager kept in memory; the agent's requests go through a harness that answers as opencode
 * does.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class FirewallTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();
  private static final String REQUEST = Harness.FIREWALL_DIRECTORY + "/request";
  private static final String RESULT = Harness.FIREWALL_DIRECTORY + "/result";
  private static final String ADDRESS = "203.0.113.10";

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired FirewallRequests requests;
  @MockitoBean ApiKeyCheck keyCheck;
  @MockitoBean VmManager vmManager;
  @MockitoSpyBean WorkspaceRuntime runtime;

  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<UUID, List<FirewallRule>> rules = new ConcurrentHashMap<>();
  private UUID workspace;

  private static FakeOpenCode start() {
    try {
      return new FakeOpenCode();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("workspaced.workspace.local-harness.url", () -> OPENCODE.endpoint().baseUrl());
    registry.add("workspaced.workspace.local-harness.password", () -> FakeOpenCode.PASSWORD);
    // Each test runs a pass itself, so the scheduled one never races it.
    registry.add("workspaced.firewall.request-interval", () -> "PT1H");
  }

  @AfterAll
  static void stop() {
    OPENCODE.close();
  }

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    OPENCODE.files.clear();
    reset(runtime);
    rules.clear();
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    when(vmManager.firewallFor(any()))
        .thenAnswer(
            call -> new VmFirewall(ADDRESS, rules.getOrDefault(call.getArgument(0), List.of())));
    when(vmManager.setFirewall(any(), anyList()))
        .thenAnswer(
            call -> {
              List<FirewallRule> set = call.getArgument(1);
              rules.put(call.getArgument(0), List.copyOf(set));
              return set;
            });
    workspace = create();
  }

  @Test
  void showsTheAddressAndStartsClosed() throws Exception {
    var body = JSON.readTree(send("GET", workspace, "", null).body());

    assertThat(body.get("address").asString()).isEqualTo(ADDRESS);
    assertThat(body.get("agentAllowed").asBoolean()).isFalse();
    assertThat(body.get("rules")).isEmpty();
  }

  @Test
  void replacesEveryRule() throws Exception {
    var response =
        send(
            "PUT",
            workspace,
            "",
            "{\"rules\":[{\"protocol\":\"tcp\",\"port\":\"8080\",\"sourceIps\":[]}]}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(rules.get(workspace))
        .containsExactly(new FirewallRule("tcp", "8080", List.of(), null));
    assertThat(JSON.readTree(response.body()).get("rules").get(0).get("port").asString())
        .isEqualTo("8080");
  }

  @Test
  void refusesWhatIsNotARuleAndPassesOnTheVmManagersReason() throws Exception {
    assertThat(send("PUT", workspace, "", "{}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", workspace, "", "{\"rules\":[{\"port\":\"80\"}]}").statusCode())
        .isEqualTo(400);

    doThrow(new FirewallRuleRejectedException("ports run from 1 to 65535"))
        .when(vmManager)
        .setFirewall(any(), anyList());
    var refused = send("PUT", workspace, "", "{\"rules\":[{\"protocol\":\"tcp\",\"port\":\"0\"}]}");
    assertThat(refused.statusCode()).isEqualTo(400);
    assertThat(refused.body()).contains("ports run from 1 to 65535");
  }

  @Test
  void answersConflictWhileTheWorkspaceHasNoVmAndNotFoundForAnUnknownOne() throws Exception {
    doThrow(new IllegalStateException("no VM")).when(vmManager).firewallFor(any());

    assertThat(send("GET", workspace, "", null).statusCode()).isEqualTo(409);
    assertThat(send("GET", UUID.randomUUID(), "", null).statusCode()).isEqualTo(404);
  }

  @Test
  void carriesOutWhatTheAgentAskedOnceTheUserAllowsIt() throws Exception {
    OPENCODE.files.put(REQUEST, "open 8080\n");
    requests.run();
    // Never read while the user has not allowed it.
    assertThat(OPENCODE.files).doesNotContainKey(RESULT);
    verify(vmManager, never()).setFirewall(any(), anyList());

    assertThat(send("PUT", workspace, "/agent", "{\"allowed\":true}").statusCode()).isEqualTo(204);
    requests.run();

    assertThat(OPENCODE.files.get(RESULT)).isEqualTo(ADDRESS + "\ntcp 8080 from \n");
    assertThat(OPENCODE.files).doesNotContainKey(REQUEST);
    assertThat(rules.get(workspace))
        .containsExactly(new FirewallRule("tcp", "8080", List.of(), "Opened by the agent"));
    // The user sees what the agent did.
    var shown = JSON.readTree(send("GET", workspace, "", null).body());
    assertThat(shown.get("agentAllowed").asBoolean()).isTrue();
    assertThat(shown.get("rules")).hasSize(1);
  }

  @Test
  void opensOnceForTheSamePortAndClosesIt() throws Exception {
    send("PUT", workspace, "/agent", "{\"allowed\":true}");

    for (var request :
        List.of("open 3000/udp", "open 3000/udp 10.0.0.0/8", "open icmp", "open 3000")) {
      OPENCODE.files.put(REQUEST, request);
      requests.run();
    }
    assertThat(rules.get(workspace))
        .containsExactly(
            new FirewallRule("udp", "3000", List.of("10.0.0.0/8"), "Opened by the agent"),
            new FirewallRule("icmp", null, List.of(), "Opened by the agent"),
            new FirewallRule("tcp", "3000", List.of(), "Opened by the agent"));

    OPENCODE.files.put(REQUEST, "close 3000/udp");
    requests.run();
    OPENCODE.files.put(REQUEST, "close icmp");
    requests.run();
    assertThat(rules.get(workspace))
        .containsExactly(new FirewallRule("tcp", "3000", List.of(), "Opened by the agent"));

    OPENCODE.files.put(REQUEST, "list");
    requests.run();
    assertThat(OPENCODE.files.get(RESULT)).isEqualTo(ADDRESS + "\ntcp 3000 from \n");
  }

  @Test
  void answersARefusalForTheAgentToRead() throws Exception {
    send("PUT", workspace, "/agent", "{\"allowed\":true}");

    OPENCODE.files.put(REQUEST, "open everything");
    requests.run();
    assertThat(OPENCODE.files.get(RESULT)).startsWith("error: not a request");

    doThrow(new FirewallRuleRejectedException("ports run from 1 to 65535"))
        .when(vmManager)
        .setFirewall(any(), anyList());
    OPENCODE.files.put(REQUEST, "open 0");
    requests.run();
    assertThat(OPENCODE.files.get(RESULT)).isEqualTo("error: ports run from 1 to 65535\n");
  }

  @Test
  void leavesARequestAloneWhileTheWorkspaceIsStopped() throws Exception {
    send("PUT", workspace, "/agent", "{\"allowed\":true}");
    doReturn(Optional.empty()).when(runtime).harness(workspace);
    OPENCODE.files.put(REQUEST, "open 8080");
    requests.run();
    assertThat(OPENCODE.files).doesNotContainKey(RESULT);
  }

  @Test
  void refusesASwitchWithNoValueOrWorkspace() throws Exception {
    assertThat(send("PUT", workspace, "/agent", "{}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", UUID.randomUUID(), "/agent", "{\"allowed\":true}").statusCode())
        .isEqualTo(404);
  }

  private UUID create() {
    return workspaces
        .create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1", null)
        .id();
  }

  private HttpResponse<String> send(String method, UUID id, String path, String json)
      throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/v1/workspaces/" + id + "/firewall" + path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
