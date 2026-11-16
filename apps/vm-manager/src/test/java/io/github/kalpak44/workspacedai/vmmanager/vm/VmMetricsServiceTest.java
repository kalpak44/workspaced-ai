package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.vmmanager.MutableClock;
import io.github.kalpak44.workspacedai.vmmanager.agent.AgentHub;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.FakeHetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerProperties;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.ServerMetrics;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.ServerType;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Against Hetzner kept in memory and a hand-moved clock, so caching and staleness are exact. */
class VmMetricsServiceTest {

  final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
  final FakeHetznerClient hetzner = new FakeHetznerClient();
  final VmRepository vms = new InMemoryVmRepository();
  final HetznerProperties hetznerProperties =
      new HetznerProperties(
          "t0ken",
          URI.create("http://unused"),
          "nbg1",
          "ubuntu-24.04",
          List.of(),
          Duration.ofSeconds(1),
          null);
  final VmService vmService =
      new VmService(
          vms,
          new VmOptionService(
              new VmProperties(List.of("cx23"), BigDecimal.ZERO),
              hetznerProperties,
              hetzner,
              clock),
          hetzner,
          hetznerProperties,
          () -> true,
          new VmSecretService(vms, new InMemorySecretStore()),
          new VmFirewallService(vms, hetzner, () -> true, clock),
          new AgentHub() {
            @Override
            public Optional<String> bootstrap(UUID vmId) {
              return Optional.empty();
            }

            @Override
            public void revoke(UUID vmId) {}
          },
          _ -> {},
          clock);
  final VmMetricsService service =
      new VmMetricsService(
          vmService,
          new VmOptionService(
              new VmProperties(List.of("cx23"), BigDecimal.ZERO),
              hetznerProperties,
              hetzner,
              clock),
          hetzner,
          clock);
  final Vm vm = vm("cx23");

  VmMetricsServiceTest() {
    hetzner.types = List.of(type("cx23", 2, 4.0), type("cx11", 1, 2.0));
    hetzner.metrics =
        new ServerMetrics(
            List.of(clock.instant().minusSeconds(60), clock.instant()),
            Map.of("cpu", Arrays.asList(150.0, null), "network.0.pps.in", List.of(1.0, 2.0)));
  }

  static ServerType type(String name, int cores, double memoryGb) {
    var price = new ServerType.Price("nbg1", new BigDecimal("5.49"), new BigDecimal("6.59"));
    return new ServerType(
        name, cores, memoryGb, 40, "shared", "x86", false, List.of(price), List.of());
  }

  Vm vm(String serverType) {
    return vms.save(
        new Vm(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            serverType,
            7,
            VmStatus.READY,
            null,
            null,
            clock.instant(),
            null));
  }

  @Test
  void answersCpuAsAShareOfEveryCoreAndEverySeriesAtEveryTimestamp() {
    var metrics = service.metrics(vm.id(), MetricsRange.HOUR);
    assertThat(metrics.cores()).isEqualTo(2);
    assertThat(metrics.memoryGb()).isEqualTo(4.0);
    assertThat(metrics.series().get("cpu")).containsExactly(75.0, null);
    assertThat(metrics.series().get("networkInPackets")).containsExactly(1.0, 2.0);
    assertThat(metrics.series().get("diskWriteBytes")).containsExactly(null, null);
    assertThat(metrics.series()).containsOnlyKeys(VmMetricsService.SERIES.values());
    assertThat(hetzner.metricsCalls)
        .containsExactly("7 2026-11-14T09:00:00Z 2026-11-14T10:00:00Z PT1M");
  }

  @Test
  void reusesHetznersAnswerPerRangeUntilItIsDue() {
    service.metrics(vm.id(), MetricsRange.LIVE);
    service.metrics(vm.id(), MetricsRange.HOUR);
    clock.advance(Duration.ofSeconds(29));
    service.metrics(vm.id(), MetricsRange.LIVE);
    assertThat(hetzner.metricsCalls).hasSize(2);
    clock.advance(Duration.ofSeconds(1));
    service.metrics(vm.id(), MetricsRange.LIVE);
    assertThat(hetzner.metricsCalls).hasSize(3);
  }

  @Test
  void sizesAVmOfATypeNoLongerOffered() {
    var old = vm("cx11");
    var metrics = service.metrics(old.id(), MetricsRange.HOUR);
    assertThat(metrics.cores()).isEqualTo(1);
    assertThat(metrics.series().get("cpu")).containsExactly(150.0, null);
  }

  @Test
  void answersTheMemoryReadingOnlyWhileItIsFresh() {
    assertThat(service.metrics(vm.id(), MetricsRange.LIVE).memory()).isNull();
    service.report(vm.id(), 4096, 1024);
    assertThat(service.metrics(vm.id(), MetricsRange.LIVE).memory())
        .isEqualTo(new VmMemory(4096, 1024, clock.instant()));
    clock.advance(VmMetricsService.MEMORY_FRESH_FOR);
    assertThat(service.metrics(vm.id(), MetricsRange.LIVE).memory()).isNull();
  }

  @Test
  void forgetsADeletedVm() {
    service.report(vm.id(), 4096, 1024);
    service.metrics(vm.id(), MetricsRange.LIVE);
    service.forget(new VmEvent.Deleted(vm.id()));
    vms.save(vm);
    assertThat(service.metrics(vm.id(), MetricsRange.LIVE).memory()).isNull();
    assertThat(hetzner.metricsCalls).hasSize(2);
  }

  @Test
  void refusesARangeNotOfferedAndAVmItDoesNotHave() {
    assertThatThrownBy(() -> MetricsRange.of("1y"))
        .isInstanceOf(MetricsRangeNotOfferedException.class)
        .hasMessageContaining("live, 1h, 24h, 7d, 30d");
    assertThatThrownBy(() -> service.metrics(UUID.randomUUID(), MetricsRange.LIVE))
        .isInstanceOf(VmNotFoundException.class);
  }
}
