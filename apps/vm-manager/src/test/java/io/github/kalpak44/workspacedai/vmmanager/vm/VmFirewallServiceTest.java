package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.vmmanager.MutableClock;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.FakeHetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.Firewall;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.FirewallRule;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VmFirewallServiceTest {

  final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
  final FakeHetznerClient hetzner = new FakeHetznerClient();
  final VmRepository vms = new InMemoryVmRepository();
  boolean inventoryLoaded = true;
  final VmFirewallService service =
      new VmFirewallService(vms, hetzner, () -> inventoryLoaded, clock);

  @Test
  void setsTheRulesOfTheVmsOwnFirewallOnly() {
    var vm = vm();
    var mine = service.create(vm.id(), vm.workspaceId());
    var other = vm();
    service.create(other.id(), other.workspaceId());

    service.setRules(vm.id(), List.of(rule("tcp", "443", List.of())));

    assertThat(hetzner.firewalls.get(mine).rules()).hasSize(1);
    assertThat(service.rules(other.id())).isEmpty();
  }

  @Test
  void refusesAVmWithNoFirewallOrNoVmAtAll() {
    var vm = vm().id();
    var unknown = UUID.randomUUID();

    assertThatThrownBy(() -> service.rules(vm)).isInstanceOf(FirewallMissingException.class);
    assertThatThrownBy(() -> service.rules(unknown)).isInstanceOf(VmNotFoundException.class);
  }

  @Test
  void letsInAnyoneWhenARuleNamesNoSource() {
    assertThat(VmFirewallService.validate(List.of(rule("udp", "53", null))).getFirst().sourceIps())
        .containsExactly("0.0.0.0/0", "::/0");
  }

  @Test
  void normalisesWhatItKeeps() {
    var rule =
        VmFirewallService.validate(
                List.of(
                    new FirewallRule(
                        " TCP ", " 8000-8100 ", List.of("192.0.2.7", "2001:db8::/32"), "  ")))
            .getFirst();

    assertThat(rule)
        .isEqualTo(
            new FirewallRule(
                "tcp", "8000-8100", List.of("192.0.2.7/32", "2001:db8:0:0:0:0:0:0/32"), null));
  }

  @Test
  void refusesWhatHetznerWould() {
    for (var rule :
        List.of(
            rule("sctp", "80", List.of()),
            rule("tcp", null, List.of()),
            rule("tcp", "0", List.of()),
            rule("tcp", "65536", List.of()),
            rule("tcp", "90-80", List.of()),
            rule("tcp", "80-80", List.of()),
            rule("tcp", "http", List.of()),
            rule("icmp", "80", List.of()),
            rule("tcp", "80", List.of("10.0.0.1/8")),
            rule("tcp", "80", List.of("10.0.0.0/33")),
            rule("tcp", "80", List.of("example.com")),
            rule("tcp", "80", List.of("10.0.0.0/x")),
            new FirewallRule("tcp", "80", List.of(), "x".repeat(256)))) {
      var one = List.of(rule);
      assertThatThrownBy(() -> VmFirewallService.validate(one))
          .as(rule.toString())
          .isInstanceOf(FirewallRuleRefusedException.class);
    }
  }

  @Test
  void capsTheRulesAndTheirSources() {
    var many = Collections.nCopies(51, rule("tcp", "80", List.of()));
    var sources = java.util.stream.IntStream.range(0, 21).mapToObj(i -> "10.0.0." + i).toList();

    assertThatThrownBy(() -> VmFirewallService.validate(many))
        .isInstanceOf(FirewallRuleRefusedException.class);
    var wide = List.of(rule("tcp", "80", sources));
    assertThatThrownBy(() -> VmFirewallService.validate(wide))
        .isInstanceOf(FirewallRuleRefusedException.class);
  }

  @Test
  void deletesOnlyOldDetachedFirewallsWhoseVmIsGone() {
    var kept = vm();
    hetzner.firewalls.put(1L, firewall(1, kept.id().toString(), 0));
    hetzner.firewalls.put(2L, firewall(2, UUID.randomUUID().toString(), 0));
    hetzner.firewalls.put(3L, firewall(3, UUID.randomUUID().toString(), 1));
    hetzner.firewalls.put(4L, firewall(4, "not-a-uuid", 0));
    clock.advance(Duration.ofMinutes(11));
    hetzner.firewalls.put(
        5L,
        new Firewall(
            5, "vm-5", clock.instant(), labels(UUID.randomUUID().toString()), List.of(), 0));

    service.deleteOrphans();

    assertThat(hetzner.deletedFirewalls).containsExactly(2L);
  }

  @Test
  void deletesNothingBeforeTheInventoryIsLoaded() {
    inventoryLoaded = false;
    hetzner.firewalls.put(2L, firewall(2, UUID.randomUUID().toString(), 0));
    clock.advance(Duration.ofHours(1));

    service.deleteOrphans();

    assertThat(hetzner.deletedFirewalls).isEmpty();
  }

  private Vm vm() {
    return vms.save(
        new Vm(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "cx23",
            1,
            VmStatus.READY,
            "203.0.113.1",
            clock.instant(),
            null));
  }

  private Firewall firewall(long id, String vmId, int servers) {
    return new Firewall(id, "vm-" + id, clock.instant(), labels(vmId), List.of(), servers);
  }

  private static Map<String, String> labels(String vmId) {
    return Map.of("managed-by", "workspaced-ai-vm-manager", "vm-id", vmId);
  }

  private static FirewallRule rule(String protocol, String port, List<String> sources) {
    return new FirewallRule(protocol, port, sources, null);
  }
}
