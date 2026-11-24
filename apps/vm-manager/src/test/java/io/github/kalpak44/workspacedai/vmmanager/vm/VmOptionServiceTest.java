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

  static ServerType type(String name, String gross, String... locations) {
    return type(name, gross, "x86", false, locations);
  }

  static ServerType type(
      String name, String gross, String architecture, boolean deprecated, String... locations) {
    var prices =
        Arrays.stream(locations)
            .map(l -> new ServerType.Price(l, new BigDecimal(gross), new BigDecimal(gross)))
            .toList();
    return new ServerType(name, 2, 4.0, 40, "shared", architecture, deprecated, prices, List.of());
  }

  static ServerType soldOut(ServerType type) {
    return new ServerType(
        type.name(),
        type.cores(),
        type.memoryGb(),
        type.diskGb(),
        type.cpuType(),
        type.architecture(),
        type.deprecated(),
        type.prices(),
        type.prices().stream().map(ServerType.Price::location).toList());
  }

  VmOptionService service(List<String> recommended) {
    return service(recommended, List.of());
  }

  VmOptionService service(List<String> recommended, List<String> fallbackLocations) {
    return new VmOptionService(
        new VmProperties(recommended, new BigDecimal("60")),
        new HetznerProperties(
            "t0ken",
            URI.create("http://unused"),
            "nbg1",
            "ubuntu-24.04",
            List.of(),
            Duration.ofSeconds(1),
            null,
            fallbackLocations),
        hetzner,
        clock);
  }

  @Test
  void takesATypeSoldOutInTheLocationFromTheFirstFallbackThatHasIt() {
    var prices =
        List.of(
            new ServerType.Price("nbg1", new BigDecimal("5.49"), new BigDecimal("6.59")),
            new ServerType.Price("fsn1", new BigDecimal("5.49"), new BigDecimal("6.59")),
            new ServerType.Price("hel1", new BigDecimal("5.49"), new BigDecimal("6.59")));
    hetzner.types =
        List.of(
            new ServerType("cx23", 2, 4, 40, "shared", "x86", false, prices, List.of("nbg1")),
            new ServerType(
                "cx33", 4, 8, 80, "shared", "x86", false, prices, List.of("nbg1", "fsn1", "hel1")));

    var options = service(List.of("cx23", "cx33"), List.of("fsn1", "hel1")).list();

    assertThat(options).extracting(VmOption::location).containsExactly("fsn1", "nbg1");
    assertThat(options).extracting(VmOption::available).containsExactly(true, false);
  }

  @Test
  void offersWhatIsOnSaleInTheLocationCheapestFirst() {
    hetzner.types =
        List.of(
            type("cx33", "10.19", "nbg1", "hel1"),
            type("cx23", "6.59", "nbg1"),
            type("cx22", "4.51", "x86", true, "nbg1"),
            type("cx43", "19.19", "hel1"));

    var options = service(List.of("cx23", "cx33", "cx22", "cx43", "cx53")).list();

    assertThat(options).extracting(VmOption::serverType).containsExactly("cx23", "cx33");
    assertThat(options).extracting(VmOption::isDefault).containsExactly(true, false);
    assertThat(options).allSatisfy(o -> assertThat(o.location()).isEqualTo("nbg1"));
    assertThat(options.getFirst().monthlyGross()).isEqualTo(new BigDecimal("6.59"));
  }

  @Test
  void addsOtherX86SizesNoSmallerThanTheDefaultUpToTheCap() {
    hetzner.types =
        List.of(
            soldOut(type("cx23", "6.59", "nbg1")),
            type("cpx22", "23.39", "nbg1"),
            type("ccx13", "51.59", "nbg1"),
            type("ccx23", "103.19", "nbg1"),
            type("cax21", "12.59", "arm", false, "nbg1"),
            new ServerType(
                "cpx02",
                1,
                1.0,
                20,
                "shared",
                "x86",
                false,
                List.of(new ServerType.Price("nbg1", BigDecimal.TEN, BigDecimal.TEN)),
                List.of()));

    var options = service(List.of("cx23")).list();

    assertThat(options).extracting(VmOption::serverType).containsExactly("cx23", "cpx22", "ccx13");
    assertThat(options).extracting(VmOption::recommended).containsExactly(true, false, false);
    assertThat(options).extracting(VmOption::available).containsExactly(false, true, true);
  }

  @Test
  void pricesTheWholeVmServerAndIpv4Address() {
    hetzner.types = List.of(type("cx23", "6.59", "nbg1"));
    hetzner.ipv4Prices =
        List.of(
            new ServerType.Price("hel1", BigDecimal.ONE, BigDecimal.ONE),
            new ServerType.Price("nbg1", new BigDecimal("0.50"), new BigDecimal("0.60")));

    var cx23 = service(List.of("cx23")).list().getFirst();

    assertThat(cx23.monthlyNet()).isEqualTo(new BigDecimal("7.09"));
    assertThat(cx23.monthlyGross()).isEqualTo(new BigDecimal("7.19"));
    assertThat(cx23.costs())
        .containsExactly(
            new VmCost("server", new BigDecimal("6.59"), new BigDecimal("6.59")),
            new VmCost("ipv4", new BigDecimal("0.50"), new BigDecimal("0.60")));
  }

  @Test
  void capsAStandInByItsWholeCost() {
    hetzner.types = List.of(type("cx23", "6.59", "nbg1"), type("cpx32", "59.50", "nbg1"));
    hetzner.ipv4Prices =
        List.of(new ServerType.Price("nbg1", new BigDecimal("0.50"), new BigDecimal("0.60")));

    assertThat(service(List.of("cx23")).list())
        .extracting(VmOption::serverType)
        .containsExactly("cx23");
  }

  @Test
  void marksATypeSoldOutOnlyWhereHetznerSaysSo() {
    var sold =
        new ServerType(
            "cx33",
            4,
            8.0,
            80,
            "shared",
            "x86",
            false,
            List.of(new ServerType.Price("nbg1", BigDecimal.ONE, BigDecimal.ONE)),
            List.of("hel1"));
    hetzner.types = List.of(type("cx23", "6.59", "nbg1"), sold);

    assertThat(service(List.of("cx23", "cx33")).list())
        .extracting(VmOption::available)
        .containsExactly(true, true);
  }

  @Test
  void asksHetznerAgainOnlyOnceTheCacheHasExpired() {
    hetzner.types = List.of(type("cx23", "6.59", "nbg1"));
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
    hetzner.types = List.of(type("cx23", "6.59", "nbg1"));
    assertThat(service.list()).hasSize(1);
  }
}
