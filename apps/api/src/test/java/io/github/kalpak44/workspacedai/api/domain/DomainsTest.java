package io.github.kalpak44.workspacedai.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.compute.VmAddress;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
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
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** DNS and the VM manager stand in memory; the database and the signing are real. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class DomainsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DnsProvider GODADDY =
      new DnsProvider("GoDaddy", "https://dcc.godaddy.com/manage");

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired WorkspaceDomainRepository domains;
  @Autowired DomainService service;
  @MockitoBean ApiKeyCheck keyCheck;
  @MockitoBean VmManager vmManager;
  @MockitoBean DnsLookup dns;
  @MockitoBean DomainConnect domainConnect;
  @MockitoBean Harness harness;
  @MockitoSpyBean WorkspaceRuntime runtime;

  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<String, List<String>> resolves = new ConcurrentHashMap<>();
  private UUID workspace;
  private UUID vm;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var key =
        Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
    registry.add(
        "workspaced.domains.private-key",
        () -> "-----BEGIN PRIVATE KEY-----\n" + key + "\n-----END PRIVATE KEY-----");
    // Each test runs a check itself, so the scheduled one never races it.
    registry.add("workspaced.domains.check-interval", () -> "PT1H");
  }

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    resolves.clear();
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace =
        workspaces
            .create(TestUsers.ADMIN, "Site", ModelProvider.OPENROUTER, "sk-or-1", null, "UTC")
            .id();
    vm = UUID.randomUUID();
    when(vmManager.addressFor(any()))
        .thenAnswer(
            call -> new VmAddress(vm, "203.0.113.10", "2001:db8::1", vm + ".vm.workspaced.online"));
    when(dns.zoneOf(anyString()))
        .thenAnswer(
            call -> {
              String name = call.getArgument(0);
              return name.endsWith("example.com") ? Optional.of("example.com") : Optional.empty();
            });
    when(dns.ipv4(anyString()))
        .thenAnswer(call -> resolves.getOrDefault(call.getArgument(0), List.of()));
    when(domainConnect.discover(anyString(), anyString())).thenReturn(Optional.empty());
  }

  @Test
  void asksForTheApexRecordsByHandWhenTheProviderAppliesNoTemplate() throws Exception {
    var response = attach("Example.COM.");

    assertThat(response.statusCode()).isEqualTo(201);
    var body = JSON.readTree(response.body());
    assertThat(body.get("name").asString()).isEqualTo("example.com");
    assertThat(body.get("host").asString()).isEqualTo("@");
    assertThat(body.get("mode").asString()).isEqualTo("MANUAL");
    assertThat(body.get("status").asString()).isEqualTo("PENDING");
    assertThat(body.get("connectUrl").isNull()).isTrue();
    assertThat(records(body))
        .containsExactly(
            "A @ 203.0.113.10", "AAAA @ 2001:db8::1", "CNAME www " + vm + ".vm.workspaced.online");
    verify(domainConnect).discover("example.com", DomainService.APEX_TEMPLATE);
  }

  @Test
  void pointsANameUnderTheDomainAtTheVmsOwnName() throws Exception {
    var body = JSON.readTree(attach("https://app.example.com/path").body());

    assertThat(body.get("domain").asString()).isEqualTo("example.com");
    assertThat(body.get("host").asString()).isEqualTo("app");
    assertThat(records(body)).containsExactly("CNAME app " + vm + ".vm.workspaced.online");
    verify(domainConnect).discover("example.com", DomainService.HOST_TEMPLATE);
  }

  @Test
  void sendsTheUserToTheirProviderWithASignedLinkWhileItIsPending() throws Exception {
    when(domainConnect.discover("example.com", DomainService.HOST_TEMPLATE))
        .thenReturn(Optional.of(GODADDY));

    var body = JSON.readTree(attach("app.example.com").body());

    assertThat(body.get("mode").asString()).isEqualTo("DOMAIN_CONNECT");
    assertThat(body.get("providerName").asString()).isEqualTo("GoDaddy");
    var link = body.get("connectUrl").asString();
    assertThat(link)
        .startsWith(
            "https://dcc.godaddy.com/manage/v2/domainTemplates/providers/workspaced.online"
                + "/services/workspace/apply?domain=example.com&host=app&vmid="
                + vm)
        .contains("&state=" + body.get("id").asString(), "&sig=", "&key=_dck1")
        .contains(
            "redirect_uri=https%3A%2F%2Fai.workspaced.online%2Fworkspaces%2F"
                + workspace
                + "%2Fsettings%2Fdomains");

    resolves.put("app.example.com", List.of("203.0.113.10"));
    service.check();
    assertThat(only().get("connectUrl").isNull()).isTrue();
  }

  @Test
  void becomesActiveOnlyOncePublicDnsReachesTheVmAndPendingAgainWhenItStops() throws Exception {
    attach("app.example.com");

    resolves.put("app.example.com", List.of("198.51.100.7"));
    service.check();
    assertThat(only().get("status").asString()).isEqualTo("PENDING");

    resolves.put("app.example.com", List.of("203.0.113.10"));
    service.check();
    var active = only();
    assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(active.get("verifiedAt").isNull()).isFalse();

    resolves.remove("app.example.com");
    service.check();
    assertThat(only().get("status").asString()).isEqualTo("PENDING");
  }

  @Test
  void tellsTheAgentWhichNamesReachTheWorkspaceOnlyWhenThatChanges() throws Exception {
    var endpoint = new HarnessEndpoint(URI.create("http://harness:4096"), "pw");
    doReturn(Optional.of(endpoint)).when(runtime).harness(workspace);
    attach("example.com");
    attach("app.example.com");

    resolves.put("example.com", List.of("203.0.113.10"));
    service.check();
    service.check();
    verify(harness, times(1)).writeDomains(eq(endpoint), anyString());
    verify(harness).writeDomains(endpoint, "example.com\nwww.example.com\n");

    resolves.clear();
    service.check();
    verify(harness).writeDomains(endpoint, "");
  }

  @Test
  void keepsItsStatusWhenDnsCannotBeAsked() throws Exception {
    attach("app.example.com");
    resolves.put("app.example.com", List.of("203.0.113.10"));
    service.check();
    when(dns.ipv4("app.example.com")).thenThrow(new DnsLookupException("timeout", null));

    service.check();

    assertThat(only().get("status").asString()).isEqualTo("ACTIVE");
  }

  @Test
  void letsAClaimOnSomeoneElsesNameBlockNobody() throws Exception {
    var squatter =
        workspaces
            .create(TestUsers.ADMIN, "Other", ModelProvider.OPENROUTER, "sk-or-1", null, "UTC")
            .id();
    assertThat(send("POST", squatter, "", "{\"name\":\"app.example.com\"}").statusCode())
        .isEqualTo(201);
    attach("app.example.com");

    // Both point at the same address in this test, so the first to be checked wins and the
    // second stays pending rather than failing the pass.
    resolves.put("app.example.com", List.of("203.0.113.10"));
    service.check();

    assertThat(domains.findAll())
        .extracting(WorkspaceDomainEntity::getStatus)
        .containsExactlyInAnyOrder(DomainStatus.ACTIVE, DomainStatus.PENDING);
  }

  @Test
  void answersTheSameNameAgainInsteadOfAttachingItTwice() throws Exception {
    var first = JSON.readTree(attach("app.example.com").body());
    var again = JSON.readTree(attach("APP.example.com").body());

    assertThat(again.get("id")).isEqualTo(first.get("id"));
    assertThat(JSON.readTree(send("GET", workspace, "", null).body())).hasSize(1);
  }

  @Test
  void detachesAndSaysWhichRecordsToRemove() throws Exception {
    var id = JSON.readTree(attach("app.example.com").body()).get("id").asString();

    var response = send("DELETE", workspace, "/" + id, null);

    assertThat(response.statusCode()).isEqualTo(200);
    var removed = JSON.readTree(response.body());
    assertThat(removed.get(0).get("name").asString()).isEqualTo("app.example.com");
    assertThat(JSON.readTree(send("GET", workspace, "", null).body())).isEmpty();
    assertThat(send("DELETE", workspace, "/" + id, null).statusCode()).isEqualTo(404);
  }

  @Test
  void refusesWhatIsNotADomainOfTheUsersOwn() throws Exception {
    assertThat(attach("not a domain").statusCode()).isEqualTo(400);
    assertThat(attach("localhost").statusCode()).isEqualTo(400);
    assertThat(attach("app.workspaced.online").statusCode()).isEqualTo(400);
    assertThat(attach("nowhere.invalid").statusCode()).isEqualTo(400);
    assertThat(send("POST", workspace, "", "{}").statusCode()).isEqualTo(400);
    verify(vmManager, never()).addressFor(any());
  }

  @Test
  void answersWhyItCannotAttachYet() throws Exception {
    when(dns.zoneOf("down.example.com")).thenThrow(new DnsLookupException("timeout", null));
    assertThat(attach("down.example.com").statusCode()).isEqualTo(503);

    when(vmManager.addressFor(eq(workspace))).thenThrow(new IllegalStateException("no VM"));
    assertThat(attach("app.example.com").statusCode()).isEqualTo(409);

    when(vmManager.addressFor(eq(workspace)))
        .thenReturn(new VmAddress(vm, "203.0.113.10", null, null));
    assertThat(attach("app.example.com").statusCode()).isEqualTo(503);

    assertThat(send("GET", UUID.randomUUID(), "", null).statusCode()).isEqualTo(404);
  }

  @Test
  void capsHowManyAWorkspaceHas() throws Exception {
    for (var i = 0; i < 20; i++) {
      assertThat(attach("n" + i + ".example.com").statusCode()).isEqualTo(201);
    }
    assertThat(attach("one-more.example.com").statusCode()).isEqualTo(400);
  }

  @Test
  void normalizesWhatAUserPastes() {
    assertThat(DomainService.normalize(" HTTPS://Shop.Example.com:8443/cart?x=1 "))
        .contains("shop.example.com");
    assertThat(DomainService.normalize("bücher.example")).contains("xn--bcher-kva.example");
    assertThat(DomainService.normalize("-bad.example.com")).isEmpty();
    assertThat(DomainService.normalize("example.123")).isEmpty();
    assertThat(DomainService.normalize("*.example.com")).isEmpty();
  }

  private JsonNode only() throws Exception {
    var list = JSON.readTree(send("GET", workspace, "", null).body());
    assertThat(list).hasSize(1);
    return list.get(0);
  }

  private static List<String> records(JsonNode domain) {
    return StreamSupport.stream(domain.get("records").spliterator(), false)
        .map(
            r ->
                r.get("type").asString()
                    + " "
                    + r.get("host").asString()
                    + " "
                    + r.get("value").asString())
        .toList();
  }

  private HttpResponse<String> attach(String name) throws Exception {
    return send("POST", workspace, "", JSON.writeValueAsString(Map.of("name", name)));
  }

  private HttpResponse<String> send(String method, UUID id, String path, String json)
      throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/v1/workspaces/" + id + "/domains" + path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
