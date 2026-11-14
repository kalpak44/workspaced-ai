package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.vmmanager.MutableClock;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.FakeHetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerProperties;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.ServerType;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Against Hetzner kept in memory, so what is offered is decided here, not by the network. */
class VmOptionServiceTest {

  final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
  final FakeHetznerClient hetzner = new FakeHetznerClient();

  static ServerType type(String name, boolean deprecated, String... locations) {
    var prices =
        Arrays.stream(locations)
            .map(l -> new ServerType.Price(l, new BigDecimal("5.49"), new BigDecimal("6.59")))
            .toList();
    return new ServerType(name, 2, 4.0, 40, "shared", "x86", deprecated, prices);
  }

  VmOptionService service(List<String> offered) {
    return new VmOptionService(
        new VmProperties(offered),
        new HetznerProperties(
            "t0ken",
            URI.create("http://unused"),
            "nbg1",
            "ubuntu-24.04",
            List.of(),
            Duration.ofSeconds(1)),
        hetzner,
        clock);
  }

  @Test
  void offersWhatIsOnSaleInTheLocationInConfiguredOrder() {
    hetzner.types =
        List.of(
            type("cx23", false, "nbg1"),
            type("cx33", false, "nbg1", "hel1"),
            type("cx22", true, "nbg1"),
            type("cx43", false, "hel1"));

    var options = service(List.of("cx33", "cx23", "cx22", "cx43", "cx53")).list();

    assertThat(options).extracting(VmOption::serverType).containsExactly("cx33", "cx23");
    assertThat(options).extracting(VmOption::isDefault).containsExactly(true, false);
    assertThat(options).allSatisfy(o -> assertThat(o.location()).isEqualTo("nbg1"));
    assertThat(options.getFirst().monthlyGross()).isEqualTo(new BigDecimal("6.59"));
  }

  @Test
  void asksHetznerAgainOnlyOnceTheCacheHasExpired() {
    hetzner.types = List.of(type("cx23", false, "nbg1"));
    var service = service(List.of("cx23"));

    service.list();
    clock.advance(VmOptionService.CACHE_FOR.minusSeconds(1));
    service.list();
    assertThat(hetzner.typeCalls).isEqualTo(1);

    clock.advance(Duration.ofSeconds(1));
    service.list();
    assertThat(hetzner.typeCalls).isEqualTo(2);
  }

  @Test
  void letsAHetznerFailureThroughAndCachesNothing() {
    var service = service(List.of("cx23"));
    hetzner.failure = new HetznerException.NotConfigured();

    assertThatThrownBy(service::list).isInstanceOf(HetznerException.NotConfigured.class);

    hetzner.failure = null;
    hetzner.types = List.of(type("cx23", false, "nbg1"));
    assertThat(service.list()).hasSize(1);
  }
}
