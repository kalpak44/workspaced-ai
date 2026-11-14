package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.util.List;
import java.util.Map;

/**
 * A spread placement group: Hetzner puts each of its servers on a different physical host.
 *
 * @param servers the ids of the servers in it
 */
public record PlacementGroup(long id, String name, List<Long> servers, Map<String, String> labels) {

  /** The most servers Hetzner allows in one spread group. */
  public static final int SPREAD_LIMIT = 10;

  public boolean isFull() {
    return servers.size() >= SPREAD_LIMIT;
  }
}
