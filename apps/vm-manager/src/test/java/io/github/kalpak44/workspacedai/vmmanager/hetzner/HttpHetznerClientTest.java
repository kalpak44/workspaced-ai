package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpHetznerClientTest {

  final FakeHetzner hetzner = new FakeHetzner();

  @AfterEach
  void tearDown() {
    hetzner.close();
  }

  @Test
  void readsEveryPageWithTheToken() {
    var types = client("t0ken").serverTypes();
    assertThat(types).extracting(ServerType::name).containsExactly("cx23", "cx22", "cx33", "cx43");
    assertThat(hetzner.authorizations).containsOnly("Bearer t0ken").hasSize(2);
  }

  @Test
  void keepsWhatAVmIsSizedAndPricedBy() {
    var cx23 = client("t0ken").serverTypes().getFirst();
    assertThat(cx23.cores()).isEqualTo(2);
    assertThat(cx23.memoryGb()).isEqualTo(4.0);
    assertThat(cx23.diskGb()).isEqualTo(40);
    assertThat(cx23.cpuType()).isEqualTo("shared");
    assertThat(cx23.architecture()).isEqualTo("x86");
    assertThat(cx23.deprecated()).isFalse();
    assertThat(cx23.priceIn("nbg1"))
        .hasValueSatisfying(
            price -> assertThat(price.monthlyNet()).isEqualTo(new BigDecimal("5.49")));
    assertThat(cx23.priceIn("nbg1"))
        .hasValueSatisfying(
            price -> assertThat(price.monthlyGross()).isEqualTo(new BigDecimal("6.59")));
    assertThat(cx23.priceIn("ash")).isEmpty();
  }

  @Test
  void marksADeprecatedType() {
    assertThat(client("t0ken").serverTypes().get(1).deprecated()).isTrue();
  }

  @Test
  void neverCallsHetznerWithoutAToken() {
    assertThatThrownBy(() -> client(" ").serverTypes())
        .isInstanceOf(HetznerException.NotConfigured.class);
    assertThatThrownBy(() -> client(null).serverTypes())
        .isInstanceOf(HetznerException.NotConfigured.class);
    assertThat(hetzner.requests).hasValue(0);
  }

  @Test
  void reportsAFailedAnswer() {
    hetzner.status = 401;
    assertThatThrownBy(() -> client("bad").serverTypes())
        .isInstanceOf(HetznerException.class)
        .isNotInstanceOf(HetznerException.NotConfigured.class)
        .hasMessageContaining("401");
  }

  @Test
  void createsAServerAndReadsBackItsIdAndAddress() {
    var spec =
        new ServerSpec(
            "vm-1",
            "cx23",
            "nbg1",
            "ubuntu-24.04",
            List.of("ops"),
            Map.of("managed-by", "workspaced-ai-vm-manager", "workspace-id", "w-1"));

    var created = client("t0ken").createServer(spec);

    assertThat(created.id()).isPositive();
    assertThat(created.ipv4()).startsWith("203.0.113.");
    var body = hetzner.createBodies.getFirst();
    assertThat(body.path("name").asString()).isEqualTo("vm-1");
    assertThat(body.path("server_type").asString()).isEqualTo("cx23");
    assertThat(body.path("location").asString()).isEqualTo("nbg1");
    assertThat(body.path("image").asString()).isEqualTo("ubuntu-24.04");
    assertThat(body.path("start_after_create").asBoolean()).isTrue();
    assertThat(body.path("ssh_keys").get(0).asString()).isEqualTo("ops");
    assertThat(body.path("labels").path("workspace-id").asString()).isEqualTo("w-1");
  }

  @Test
  void createsEveryServerBehindOneFirewallThatLetsNothingIn() {
    var client = client("t0ken");
    client.createServer(
        new ServerSpec("vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of()));
    client.createServer(
        new ServerSpec("vm-2", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of()));

    assertThat(hetzner.firewalls).hasSize(1);
    var firewall = hetzner.firewalls.values().iterator().next();
    assertThat(firewall.path("name").asString()).isEqualTo("workspaced-ai-vms");
    assertThat(firewall.path("rules")).isEmpty();
    assertThat(hetzner.createBodies)
        .allSatisfy(
            body ->
                assertThat(body.path("firewalls").get(0).path("firewall").asLong())
                    .isEqualTo(firewall.path("id").asLong()));
  }

  @Test
  void listsOnlyServersMatchingTheLabel() {
    var client = client("t0ken");
    var mine =
        client.createServer(
            new ServerSpec("vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of("app", "a")));
    client.createServer(
        new ServerSpec("vm-2", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of("app", "b")));

    var servers = client.servers("app=a");

    assertThat(servers).extracting(Server::id).containsExactly(mine.id());
    var server = servers.getFirst();
    assertThat(server.name()).isEqualTo("vm-1");
    assertThat(server.serverType()).isEqualTo("cx23");
    assertThat(server.ipv4()).isEqualTo(mine.ipv4());
    assertThat(server.labels()).containsEntry("app", "a");
  }

  @Test
  void deletesAServerAndTreatsOneAlreadyGoneAsDeleted() {
    var client = client("t0ken");
    var created =
        client.createServer(
            new ServerSpec("vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of()));

    client.deleteServer(created.id());
    assertThat(hetzner.servers).isEmpty();

    client.deleteServer(created.id());
  }

  @Test
  void reportsAFailedCreate() {
    hetzner.status = 422;
    var spec = new ServerSpec("vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of());
    var client = client("t0ken");

    assertThatThrownBy(() -> client.createServer(spec))
        .isInstanceOf(HetznerException.class)
        .hasMessageContaining("422");
  }

  private HttpHetznerClient client(String token) {
    var properties =
        new HetznerProperties(
            token,
            hetzner.url(),
            "nbg1",
            "ubuntu-24.04",
            List.of(),
            "workspaced-ai-vms",
            Duration.ofSeconds(5));
    return new HttpHetznerClient(
        properties, HttpClient.newHttpClient(), JsonMapper.builder().build());
  }
}
