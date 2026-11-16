package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerProperties;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.ServerType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The sizes Hetzner currently sells in the location, cheapest first: the configured low-cost ones,
 * and any other x86 type up to a price cap. Hetzner supplies their specs, price and whether it has
 * capacity, so none of them is restated here. The price is the VM's whole monthly cost: the server
 * and the public IPv4 address every VM gets, which Hetzner bills apart; its firewall and IPv6 are
 * free.
 */
@Service
public class VmOptionService {

  private static final Logger log = LoggerFactory.getLogger(VmOptionService.class);

  // Capacity comes and goes within minutes, and every create screen would otherwise call Hetzner.
  static final Duration CACHE_FOR = Duration.ofMinutes(1);

  static final String SERVER = "server";
  static final String IPV4 = "ipv4";

  // Every VM boots from one snapshot, which only runs on this architecture.
  private static final String ARCHITECTURE = "x86";

  private final VmProperties properties;
  private final HetznerProperties hetznerProperties;
  private final HetznerClient hetzner;
  private final Clock clock;
  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

  public VmOptionService(
      VmProperties properties,
      HetznerProperties hetznerProperties,
      HetznerClient hetzner,
      Clock clock) {
    this.properties = properties;
    this.hetznerProperties = hetznerProperties;
    this.hetzner = hetzner;
    this.clock = clock;
  }

  /**
   * Cheapest first. A type Hetzner deprecated or does not sell in the location is left out; one it
   * has sold out stays, marked unavailable, so the user sees what to wait for.
   */
  public List<VmOption> list() {
    var now = clock.instant();
    var current = snapshot.get();
    if (current != null && now.isBefore(current.takenAt().plus(CACHE_FOR))) {
      return current.options();
    }
    var options = fetch();
    snapshot.set(new Snapshot(options, now));
    return options;
  }

  public Optional<VmOption> find(String serverType) {
    return list().stream().filter(option -> option.serverType().equals(serverType)).findFirst();
  }

  String defaultServerType() {
    return properties.defaultServerType();
  }

  private List<VmOption> fetch() {
    var location = hetznerProperties.location();
    var types = hetzner.serverTypes();
    // A stand-in is never smaller than the default, which is the least a workspace runs on.
    var floor =
        types.stream()
            .filter(type -> type.name().equals(properties.defaultServerType()))
            .mapToDouble(ServerType::memoryGb)
            .findFirst()
            .orElse(0);
    var ipv4 =
        hetzner.primaryIpv4Prices().stream()
            .filter(price -> price.location().equals(location))
            .findFirst()
            .orElse(null);
    if (ipv4 == null) {
      log.warn("no IPv4 price for {}; prices leave the address out", location);
    }
    var options = new ArrayList<VmOption>();
    for (var type : types) {
      var server = type.deprecated() ? null : type.priceIn(location).orElse(null);
      if (server == null) {
        continue;
      }
      var costs = new ArrayList<VmCost>();
      costs.add(new VmCost(SERVER, server.monthlyNet(), server.monthlyGross()));
      if (ipv4 != null) {
        costs.add(new VmCost(IPV4, ipv4.monthlyNet(), ipv4.monthlyGross()));
      }
      var net = costs.stream().map(VmCost::monthlyNet).reduce(BigDecimal.ZERO, BigDecimal::add);
      var gross = costs.stream().map(VmCost::monthlyGross).reduce(BigDecimal.ZERO, BigDecimal::add);
      var recommended = properties.recommends(type.name());
      if (!(recommended || standIn(type, gross, floor))) {
        continue;
      }
      options.add(
          new VmOption(
              type.name(),
              type.name().equals(properties.defaultServerType()),
              type.cores(),
              type.memoryGb(),
              type.diskGb(),
              type.cpuType(),
              type.architecture(),
              location,
              net,
              gross,
              recommended,
              type.availableIn(location),
              List.copyOf(costs)));
    }
    for (var name : properties.serverTypes()) {
      if (options.stream().noneMatch(option -> option.serverType().equals(name))) {
        log.warn("server type {} is not on sale in {}; not offering it", name, location);
      }
    }
    options.sort(Comparator.comparing(VmOption::monthlyGross).thenComparing(VmOption::serverType));
    return List.copyOf(options);
  }

  private boolean standIn(ServerType type, BigDecimal monthlyGross, double memoryFloor) {
    return ARCHITECTURE.equals(type.architecture())
        && type.memoryGb() >= memoryFloor
        && monthlyGross.compareTo(properties.maxMonthlyGross()) <= 0;
  }

  private record Snapshot(List<VmOption> options, Instant takenAt) {}
}
