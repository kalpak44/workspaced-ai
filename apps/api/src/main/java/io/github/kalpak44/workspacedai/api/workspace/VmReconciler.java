package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.billing.BillingService;
import io.github.kalpak44.workspacedai.api.compute.ProvisionedVm;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.compute.VmPricing;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Matches the VMs the VM manager keeps against the workspaces here, for what a create leaves behind
 * when it times out or its pod dies halfway: a VM whose workspace is gone or back to a draft, which
 * Hetzner bills and nobody pays for, is deleted; a running one nothing charges for, or a demo with
 * no end, is charged or given its end.
 */
@Component
public class VmReconciler {

  private static final Logger log = LoggerFactory.getLogger(VmReconciler.class);
  // A VM reports ready only after its create returned, and a create charges right after that.
  private static final String READY = "READY";

  private final VmManager vmManager;
  private final WorkspaceRepository workspaces;
  private final UserRepository users;
  private final BillingService billing;
  private final VmPricing pricing;
  private final TransactionTemplate transactions;
  private final Duration demoLength;
  private final int maxDeletes;

  public VmReconciler(
      VmManager vmManager,
      WorkspaceRepository workspaces,
      UserRepository users,
      BillingService billing,
      VmPricing pricing,
      TransactionTemplate transactions,
      @Value("${workspaced.demo.length:PT4H}") Duration demoLength,
      @Value("${workspaced.vm-reconcile.max-deletes:3}") int maxDeletes) {
    this.vmManager = vmManager;
    this.workspaces = workspaces;
    this.users = users;
    this.billing = billing;
    this.pricing = pricing;
    this.transactions = transactions;
    this.demoLength = demoLength;
    this.maxDeletes = maxDeletes;
  }

  @Scheduled(
      fixedDelayString = "${workspaced.vm-reconcile.interval:PT10M}",
      initialDelayString = "${workspaced.vm-reconcile.interval:PT10M}")
  @SchedulerLock(name = "vm-reconcile", lockAtMostFor = "PT15M")
  public void run() {
    // VMs before workspaces: a launch commits its workspace before asking for the VM, so every VM
    // listed here already has the workspace row it will keep.
    List<ProvisionedVm> vms;
    try {
      vms = vmManager.vms();
    } catch (RuntimeException e) {
      log.warn("could not list VMs to reconcile: {}", e.getMessage());
      return;
    }
    var launched =
        workspaces.findAllByDraftFalse().stream()
            .collect(Collectors.toMap(WorkspaceEntity::getId, Function.identity()));
    deleteOrphans(vms, launched);
    for (var vm : vms) {
      var workspace = launched.get(vm.workspaceId());
      if (workspace != null && READY.equals(vm.status())) {
        try {
          settle(workspace, vm);
        } catch (RuntimeException e) {
          log.warn("could not reconcile workspace {}", workspace.getId(), e);
        }
      }
    }
  }

  private void deleteOrphans(List<ProvisionedVm> vms, Map<UUID, WorkspaceEntity> launched) {
    var orphans = vms.stream().filter(vm -> !launched.containsKey(vm.workspaceId())).toList();
    // Many at once means the workspaces could not be read right, not that many creates failed:
    // deleting them would take down paying users' VMs.
    if (orphans.size() > maxDeletes) {
      log.error(
          "{} VMs have no launched workspace, more than the {} deleted at once; deleting none",
          orphans.size(),
          maxDeletes);
      return;
    }
    for (var vm : orphans) {
      try {
        vmManager.deleteFor(vm.workspaceId());
        log.warn(
            "deleted VM {} of workspace {}, which no longer has one", vm.id(), vm.workspaceId());
      } catch (RuntimeException e) {
        log.warn("could not delete orphaned VM {}: {}", vm.id(), e.getMessage());
      }
    }
  }

  private void settle(WorkspaceEntity workspace, ProvisionedVm vm) {
    var id = workspace.getId();
    if (workspace.isDemo()) {
      if (workspace.getExpiresAt() == null) {
        transactions.executeWithoutResult(
            _ ->
                workspaces
                    .findLockedById(id)
                    .filter(locked -> locked.isDemo() && locked.getExpiresAt() == null)
                    .ifPresent(locked -> locked.setExpiresAt(Instant.now().plus(demoLength))));
        log.warn("gave demo workspace {} the end its create never set", id);
      }
      return;
    }
    var owner = workspace.getOwnerId();
    var admin = users.findById(owner).map(user -> user.getRole() == Role.ADMIN).orElse(false);
    if (admin || billing.isCharged(id)) {
      return;
    }
    var price = pricing.planPrice(vm.serverType());
    if (price.isEmpty()) {
      log.error(
          "workspace {} runs on {}, which no plan prices; it is not charged", id, vm.serverType());
      return;
    }
    if (billing.startChargingIfUncharged(id, owner, price.get())) {
      log.warn("started charging workspace {}, which its create never did", id);
    }
  }
}
