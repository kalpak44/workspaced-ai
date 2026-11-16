package io.github.kalpak44.workspacedai.api.firewall;

import io.github.kalpak44.workspacedai.api.ClusterLocks;
import io.github.kalpak44.workspacedai.api.compute.FirewallRule;
import io.github.kalpak44.workspacedai.api.compute.VmFirewall;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A workspace's firewall, kept by the VM manager on its VM. The rules live only there, so a change
 * made by the user and one made by the agent are serialized under one lock rather than reconciled.
 */
@Service
public class FirewallService {

  private final WorkspaceRepository workspaces;
  private final VmManager vmManager;
  private final ClusterLocks locks;

  public FirewallService(WorkspaceRepository workspaces, VmManager vmManager, ClusterLocks locks) {
    this.workspaces = workspaces;
    this.vmManager = vmManager;
    this.locks = locks;
  }

  public WorkspaceFirewall get(UUID workspaceId) {
    var workspace = workspace(workspaceId);
    var firewall = firewall(workspaceId);
    return new WorkspaceFirewall(
        firewall.address(), workspace.isFirewallAgentAllowed(), firewall.rules());
  }

  public WorkspaceFirewall set(UUID workspaceId, List<FirewallRule> rules) {
    var workspace = workspace(workspaceId);
    return locks.call(
        lock(workspaceId),
        () -> {
          var address = firewall(workspaceId).address();
          return new WorkspaceFirewall(
              address,
              workspace.isFirewallAgentAllowed(),
              vmManager.setFirewall(workspaceId, rules));
        });
  }

  public void setAgentAllowed(UUID workspaceId, boolean allowed) {
    if (workspaces.updateFirewallAgentAllowed(workspaceId, allowed) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + workspaceId);
    }
  }

  /**
   * Adds a rule for the port, replacing any the same protocol and port already had, so asking twice
   * leaves one rule.
   */
  WorkspaceFirewall open(UUID workspaceId, FirewallRule rule) {
    return edit(
        workspaceId,
        rules -> {
          rules.removeIf(r -> same(r, rule));
          rules.add(rule);
        });
  }

  /** Removes every rule for the port; one it never had is not an error. */
  WorkspaceFirewall close(UUID workspaceId, FirewallRule rule) {
    return edit(workspaceId, rules -> rules.removeIf(r -> same(r, rule)));
  }

  private WorkspaceFirewall edit(UUID workspaceId, Consumer<List<FirewallRule>> change) {
    var workspace = workspace(workspaceId);
    return locks.call(
        lock(workspaceId),
        () -> {
          var current = firewall(workspaceId);
          var rules = new ArrayList<>(current.rules());
          change.accept(rules);
          return new WorkspaceFirewall(
              current.address(),
              workspace.isFirewallAgentAllowed(),
              vmManager.setFirewall(workspaceId, rules));
        });
  }

  private static boolean same(FirewallRule a, FirewallRule b) {
    return a.protocol().equalsIgnoreCase(b.protocol()) && Objects.equals(a.port(), b.port());
  }

  private VmFirewall firewall(UUID workspaceId) {
    try {
      return vmManager.firewallFor(workspaceId);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "the workspace has no VM yet", e);
    }
  }

  private WorkspaceEntity workspace(UUID workspaceId) {
    return workspaces
        .findById(workspaceId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + workspaceId));
  }

  static String lock(UUID workspaceId) {
    return "firewall-" + workspaceId;
  }
}
