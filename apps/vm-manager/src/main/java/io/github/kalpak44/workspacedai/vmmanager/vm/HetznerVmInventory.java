package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.Server;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hetzner is the record of which VMs exist: every server this app creates is labelled with its VM
 * id, workspace id and user id, and those labels are read back into the repository at startup. So a
 * restart forgets only status and heartbeats, which the VMs report again, and never a server that
 * is still billing. A load that fails is retried every minute until one succeeds.
 */
@Component
public class HetznerVmInventory implements VmInventory {

  static final String MANAGED_BY = "managed-by";
  static final String MANAGER = "workspaced-ai-vm-manager";
  static final String VM_ID = "vm-id";
  static final String WORKSPACE_ID = "workspace-id";
  static final String USER_ID = "user-id";

  private static final Logger log = LoggerFactory.getLogger(HetznerVmInventory.class);

  private final HetznerClient hetzner;
  private final VmRepository vms;
  private final AtomicBoolean loaded = new AtomicBoolean();

  public HetznerVmInventory(HetznerClient hetzner, VmRepository vms) {
    this.hetzner = hetzner;
    this.vms = vms;
  }

  @Override
  public boolean isLoaded() {
    return loaded.get();
  }

  @EventListener(ApplicationReadyEvent.class)
  void loadOnStartup() {
    load();
  }

  @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
  void retryUntilLoaded() {
    if (!loaded.get()) {
      load();
    }
  }

  /** Adds each labelled server not already known; one without readable labels is skipped. */
  synchronized void load() {
    try {
      var servers = hetzner.servers(MANAGED_BY + "=" + MANAGER);
      var adopted = 0;
      for (var server : servers) {
        if (adopt(server)) {
          adopted++;
        }
      }
      loaded.set(true);
      log.info("loaded {} VMs from {} labelled Hetzner servers", adopted, servers.size());
    } catch (HetznerException e) {
      log.warn(
          "could not load VMs from Hetzner, creates are refused until it can: {}", e.getMessage());
    }
  }

  // Status and heartbeats are not kept at Hetzner; the VM reports them again once it is up.
  private boolean adopt(Server server) {
    UUID id;
    UUID workspaceId;
    UUID userId;
    try {
      id = UUID.fromString(server.labels().getOrDefault(VM_ID, ""));
      workspaceId = UUID.fromString(server.labels().getOrDefault(WORKSPACE_ID, ""));
      userId = UUID.fromString(server.labels().getOrDefault(USER_ID, ""));
    } catch (IllegalArgumentException e) {
      log.warn(
          "Hetzner server {} lacks a usable vm-id, workspace-id or user-id label; skipping it",
          server.id());
      return false;
    }
    if (vms.findById(id).isPresent()) {
      return false;
    }
    vms.save(
        new Vm(
            id,
            workspaceId,
            userId,
            server.serverType(),
            server.id(),
            VmStatus.PROVISIONING,
            server.ipv4(),
            server.created(),
            null));
    return true;
  }
}
