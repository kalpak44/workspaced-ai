package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.PlacementGroup;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One spread placement group per user, so no two of a user's VMs share a physical host and one host
 * failing takes down at most one of their workspaces. Groups are found by label, created on a
 * user's first VM and deleted once their last one is gone; Hetzner charges nothing for them.
 * Callers serialize use: {@link VmService} holds its lock around every call.
 */
@Component
public class UserPlacementGroups {

  private static final Logger log = LoggerFactory.getLogger(UserPlacementGroups.class);

  private final HetznerClient hetzner;

  public UserPlacementGroups(HetznerClient hetzner) {
    this.hetzner = hetzner;
  }

  /**
   * The group a new VM of the user's goes in, created if they have none. Null once the group holds
   * as many servers as Hetzner allows: the VM is then created outside it rather than not at all.
   */
  public Long groupFor(UUID userId) {
    var groups = hetzner.placementGroups(selector(userId));
    var group =
        groups.isEmpty()
            ? hetzner.createPlacementGroup(
                "user-" + userId,
                Map.of(
                    HetznerVmInventory.MANAGED_BY,
                    HetznerVmInventory.MANAGER,
                    HetznerVmInventory.USER_ID,
                    userId.toString()))
            : groups.getFirst();
    if (group.isFull()) {
      log.warn(
          "placement group {} already holds {} servers; the next VM of user {} is not spread",
          group.id(),
          PlacementGroup.SPREAD_LIMIT,
          userId);
      return null;
    }
    return group.id();
  }

  /**
   * Deletes the user's group once the server just deleted was its last. Best effort: Hetzner may
   * still list that server while it shuts down and refuse, and an empty group left behind costs
   * nothing and is reused by the user's next VM.
   */
  public void releaseIfEmpty(UUID userId, long deletedServer) {
    try {
      for (var group : hetzner.placementGroups(selector(userId))) {
        if (group.servers().stream().allMatch(id -> id == deletedServer)) {
          hetzner.deletePlacementGroup(group.id());
        }
      }
    } catch (HetznerException e) {
      log.info("left placement group of user {} in place: {}", userId, e.getMessage());
    }
  }

  private static String selector(UUID userId) {
    return HetznerVmInventory.MANAGED_BY
        + "="
        + HetznerVmInventory.MANAGER
        + ","
        + HetznerVmInventory.USER_ID
        + "="
        + userId;
  }
}
