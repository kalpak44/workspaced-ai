package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerProperties;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.ServerType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The configured server types, as Hetzner currently sells them. Configuration decides which sizes
 * exist; Hetzner supplies their specs and price, so neither is restated here.
 */
@Service
public class VmOptionService {

  private static final Logger log = LoggerFactory.getLogger(VmOptionService.class);

  // Specs and prices change rarely, and every create screen would otherwise call Hetzner.
  static final Duration CACHE_FOR = Duration.ofMinutes(10);

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
   * In configured order. A type Hetzner deprecated or no longer sells in the location is left out
   * and logged, rather than offered and then refused on create.
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

  private List<VmOption> fetch() {
    var location = hetznerProperties.location();
    var types =
        hetzner.serverTypes().stream()
            .collect(Collectors.toMap(ServerType::name, Function.identity(), (a, _) -> a));
    var options = new ArrayList<VmOption>();
    for (var name : properties.serverTypes()) {
      var type = types.get(name);
      var price = type == null || type.deprecated() ? null : type.priceIn(location).orElse(null);
      if (price == null) {
        log.warn("server type {} is not on sale in {}; not offering it", name, location);
        continue;
      }
      options.add(
          new VmOption(
              name,
              name.equals(properties.defaultServerType()),
              type.cores(),
              type.memoryGb(),
              type.diskGb(),
              type.cpuType(),
              type.architecture(),
              location,
              price.monthlyNet(),
              price.monthlyGross()));
    }
    return List.copyOf(options);
  }

  private record Snapshot(List<VmOption> options, Instant takenAt) {}
}
