package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.ServerType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
public class VmOptions {

  private static final Logger log = LoggerFactory.getLogger(VmOptions.class);

  // Specs and prices change rarely, and every create screen would otherwise call Hetzner.
  private static final Duration CACHE_FOR = Duration.ofMinutes(10);

  private final VmProperties properties;
  private final HetznerClient hetzner;
  private volatile Snapshot snapshot;

  public VmOptions(VmProperties properties, HetznerClient hetzner) {
    this.properties = properties;
    this.hetzner = hetzner;
  }

  /**
   * In configured order. A type Hetzner deprecated or no longer sells in the location is left out
   * and logged, rather than offered and then refused on create.
   */
  public List<VmOption> list() {
    var current = snapshot;
    if (current != null && current.takenAt().plus(CACHE_FOR).isAfter(Instant.now())) {
      return current.options();
    }
    var options = fetch();
    snapshot = new Snapshot(options, Instant.now());
    return options;
  }

  private List<VmOption> fetch() {
    var location = hetzner.location();
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
