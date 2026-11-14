package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.FakeHetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.Server;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HetznerVmInventoryTest {

  final FakeHetznerClient hetzner = new FakeHetznerClient();
  final VmRepository vms = new InMemoryVmRepository();
  final HetznerVmInventory inventory = new HetznerVmInventory(hetzner, vms);

  final UUID vmId = UUID.randomUUID();
  final UUID workspaceId = UUID.randomUUID();
  final UUID userId = UUID.randomUUID();
  final Instant created = Instant.parse("2026-11-01T08:00:00Z");

  Server server(long id, Map<String, String> labels) {
    return new Server(id, "vm-" + id, "cx33", "203.0.113.7", created, labels);
  }

  Map<String, String> labels(String vm, String workspace) {
    return Map.of(
        "managed-by",
        "workspaced-ai-vm-manager",
        "vm-id",
        vm,
        "workspace-id",
        workspace,
        "user-id",
        userId.toString());
  }

  @Test
  void rebuildsEachVmFromItsServersLabels() {
    hetzner.servers.put(7L, server(7, labels(vmId.toString(), workspaceId.toString())));

    inventory.load();

    assertThat(inventory.isLoaded()).isTrue();
    assertThat(vms.findById(vmId))
        .contains(
            new Vm(
                vmId,
                workspaceId,
                userId,
                "cx33",
                7,
                VmStatus.PROVISIONING,
                "203.0.113.7",
                created,
                null));
  }

  @Test
  void skipsServersItDidNotLabelOrCannotRead() {
    hetzner.servers.put(1L, server(1, Map.of("managed-by", "someone-else")));
    hetzner.servers.put(2L, server(2, labels("not-a-uuid", workspaceId.toString())));
    hetzner.servers.put(3L, server(3, labels(vmId.toString(), "not-a-uuid")));

    inventory.load();

    assertThat(inventory.isLoaded()).isTrue();
    assertThat(vms.findAll()).isEmpty();
  }

  @Test
  void neverOverwritesAVmItAlreadyKnows() {
    var known =
        new Vm(
            vmId, workspaceId, userId, "cx23", 7, VmStatus.READY, "203.0.113.7", created, created);
    vms.save(known);
    hetzner.servers.put(7L, server(7, labels(vmId.toString(), workspaceId.toString())));

    inventory.load();

    assertThat(vms.findById(vmId)).contains(known);
  }

  @Test
  void staysUnloadedAndRetriesWhileHetznerFails() {
    hetzner.failure = new HetznerException("Hetzner answered 503 on GET /servers");
    inventory.loadOnStartup();
    assertThat(inventory.isLoaded()).isFalse();

    hetzner.failure = null;
    hetzner.servers.put(7L, server(7, labels(vmId.toString(), workspaceId.toString())));
    inventory.retryUntilLoaded();

    assertThat(inventory.isLoaded()).isTrue();
    assertThat(vms.findByWorkspaceId(workspaceId)).hasSize(1);
    assertThat(vms.findById(vmId)).isNotEqualTo(Optional.empty());
  }
}
