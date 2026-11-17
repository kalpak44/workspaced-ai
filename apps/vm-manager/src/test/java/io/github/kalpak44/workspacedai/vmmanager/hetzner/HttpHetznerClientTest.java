package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
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
  void readsWhatAnIpv4AddressCostsInEachLocation() {
    assertThat(client("t0ken").primaryIpv4Prices())
        .containsExactly(
            new ServerType.Price("nbg1", new BigDecimal("0.50"), new BigDecimal("0.60")),
            new ServerType.Price("hel1", new BigDecimal("0.50"), new BigDecimal("0.60")));
  }

  @Test
  void readsWhereATypeIsSoldOut() {
    var types = client("t0ken").serverTypes();
    assertThat(types.getFirst().availableIn("nbg1")).isTrue();
    assertThat(types.getFirst().availableIn("hel1")).isFalse();
    assertThat(types.getFirst().availableIn("ash")).isFalse();
    // No flag at all is not sold out.
    assertThat(types.get(2).availableIn("nbg1")).isTrue();
  }

  @Test
  void alignsEveryMetricsSeriesToTheSameTimestamps() {
    var metrics =
        client("t0ken")
            .metrics(
                42,
                Instant.parse("2026-11-14T10:00:00Z"),
                Instant.parse("2026-11-14T10:01:00Z"),
                Duration.ofMinutes(1));
    assertThat(hetzner.metricsQueries)
        .containsExactly(
            "type=cpu,disk,network&start=2026-11-14T10:00:00Z&end=2026-11-14T10:01:00Z&step=60");
    assertThat(metrics.timestamps())
        .containsExactly(Instant.ofEpochSecond(1794650400), Instant.ofEpochSecond(1794650460));
    assertThat(metrics.series().get("cpu")).containsExactly(150.0, null);
    assertThat(metrics.series().get("disk.0.bandwidth.read")).containsExactly(4096.0, null);
    assertThat(metrics.series().get("network.0.pps.in")).containsExactly(1.5, 2.0);
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
            Map.of("managed-by", "workspaced-ai-vm-manager", "workspace-id", "w-1"),
            "#cloud-config\n",
            7);

    var created = client("t0ken").createServer(spec);

    assertThat(created.id()).isPositive();
    assertThat(created.ipv4()).startsWith("203.0.113.");
    assertThat(created.ipv6()).startsWith("2001:db8:0:").endsWith(":0:0:0:1");
    var body = hetzner.createBodies.getFirst();
    assertThat(body.path("name").asString()).isEqualTo("vm-1");
    assertThat(body.path("server_type").asString()).isEqualTo("cx23");
    assertThat(body.path("location").asString()).isEqualTo("nbg1");
    assertThat(body.path("image").asString()).isEqualTo("ubuntu-24.04");
    assertThat(body.path("start_after_create").asBoolean()).isTrue();
    assertThat(body.path("ssh_keys").get(0).asString()).isEqualTo("ops");
    assertThat(body.path("labels").path("workspace-id").asString()).isEqualTo("w-1");
    assertThat(body.path("user_data").asString()).isEqualTo("#cloud-config\n");
  }

  @Test
  void createsFromTheNewestSnapshotCarryingTheLabel() {
    hetzner.snapshot(41, "workspace-vm", "2026-11-01T00:00:00Z");
    hetzner.snapshot(42, "workspace-vm", "2026-11-14T00:00:00Z");
    hetzner.snapshot(43, "other", "2026-11-15T00:00:00Z");

    client("t0ken", "image=workspace-vm")
        .createServer(
            new ServerSpec("vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of(), null, 7));

    var body = hetzner.createBodies.getFirst();
    assertThat(body.path("image").asString()).isEqualTo("42");
    assertThat(body.has("user_data")).isFalse();
  }

  @Test
  void createsNothingWhenNoSnapshotCarriesTheLabel() {
    var client = client("t0ken", "image=workspace-vm");
    var spec = new ServerSpec("vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of(), null, 7);

    assertThatThrownBy(() -> client.createServer(spec))
        .isInstanceOf(HetznerException.class)
        .hasMessageContaining("no snapshot is labelled image=workspace-vm");
    assertThat(hetzner.createBodies).isEmpty();
  }

  @Test
  void createsAServerBehindTheFirewallItIsGiven() {
    var client = client("t0ken");
    client.createServer(
        new ServerSpec("vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of(), null, 42));

    assertThat(hetzner.createBodies.getFirst().path("firewalls").get(0).path("firewall").asLong())
        .isEqualTo(42);
  }

  @Test
  void createsAFirewallThatLetsNothingInAndFindsItByLabel() {
    var client = client("t0ken");
    var id = client.createFirewall("vm-1", Map.of("vm-id", "1"));
    client.createFirewall("vm-2", Map.of("vm-id", "2"));

    var found = client.firewalls("vm-id=1");

    assertThat(found).extracting(Firewall::id).containsExactly(id);
    var firewall = found.getFirst();
    assertThat(firewall.name()).isEqualTo("vm-1");
    assertThat(firewall.labels()).containsEntry("vm-id", "1");
    assertThat(firewall.rules()).isEmpty();
    assertThat(firewall.servers()).isZero();
    assertThat(firewall.created()).isEqualTo(Instant.parse("2026-11-14T10:00:00Z"));
  }

  @Test
  void setsInboundRulesAndReadsThemBack() {
    var client = client("t0ken");
    var id = client.createFirewall("vm-1", Map.of("vm-id", "1"));

    client.setFirewallRules(
        id,
        List.of(
            new FirewallRule("tcp", "8000-8100", List.of("0.0.0.0/0", "::/0"), "Blog"),
            new FirewallRule("icmp", null, List.of("10.0.0.0/8"), null)));

    var sent = hetzner.firewalls.get(id).path("rules");
    assertThat(sent.get(0).path("direction").asString()).isEqualTo("in");
    assertThat(sent.get(1).has("port")).isFalse();
    assertThat(sent.get(1).has("description")).isFalse();
    assertThat(client.firewalls("vm-id=1").getFirst().rules())
        .containsExactly(
            new FirewallRule("tcp", "8000-8100", List.of("0.0.0.0/0", "::/0"), "Blog"),
            new FirewallRule("icmp", null, List.of("10.0.0.0/8"), null));
  }

  @Test
  void deletesAFirewallAndTreatsOneAlreadyGoneAsDeleted() {
    var client = client("t0ken");
    var id = client.createFirewall("vm-1", Map.of());

    client.deleteFirewall(id);
    assertThat(hetzner.firewalls).isEmpty();

    client.deleteFirewall(id);
  }

  @Test
  void listsOnlyServersMatchingTheLabel() {
    var client = client("t0ken");
    var mine =
        client.createServer(
            new ServerSpec(
                "vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of("app", "a"), null, 7));
    client.createServer(
        new ServerSpec(
            "vm-2", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of("app", "b"), null, 7));

    var servers = client.servers("app=a");

    assertThat(servers).extracting(Server::id).containsExactly(mine.id());
    var server = servers.getFirst();
    assertThat(server.name()).isEqualTo("vm-1");
    assertThat(server.serverType()).isEqualTo("cx23");
    assertThat(server.ipv4()).isEqualTo(mine.ipv4());
    assertThat(server.ipv6()).isEqualTo(mine.ipv6());
    assertThat(server.labels()).containsEntry("app", "a");
  }

  @Test
  void shutsAServerDownAndPowersItBackOn() {
    var client = client("t0ken");
    var created =
        client.createServer(
            new ServerSpec(
                "vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of("app", "a"), null, 7));

    client.shutdownServer(created.id());
    assertThat(client.servers("app=a").getFirst().status()).isEqualTo(Server.OFF);

    client.powerOnServer(created.id());
    assertThat(client.servers("app=a").getFirst().status()).isEqualTo("running");
  }

  @Test
  void deletesAServerAndTreatsOneAlreadyGoneAsDeleted() {
    var client = client("t0ken");
    var created =
        client.createServer(
            new ServerSpec("vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of(), null, 7));

    client.deleteServer(created.id());
    assertThat(hetzner.servers).isEmpty();

    client.deleteServer(created.id());
  }

  @Test
  void reportsAFailedCreate() {
    hetzner.status = 422;
    var spec = new ServerSpec("vm-1", "cx23", "nbg1", "ubuntu-24.04", List.of(), Map.of(), null, 7);
    var client = client("t0ken");

    assertThatThrownBy(() -> client.createServer(spec))
        .isInstanceOf(HetznerException.class)
        .hasMessageContaining("422");
  }

  private HttpHetznerClient client(String token) {
    return client(token, null);
  }

  private HttpHetznerClient client(String token, String imageSelector) {
    var properties =
        new HetznerProperties(
            token,
            hetzner.url(),
            "nbg1",
            "ubuntu-24.04",
            List.of(),
            Duration.ofSeconds(5),
            imageSelector);
    return new HttpHetznerClient(
        properties, HttpClient.newHttpClient(), JsonMapper.builder().build());
  }
}
