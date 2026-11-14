package io.github.kalpak44.workspacedai.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.compute.ProvisionedVm;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class VmWorkspaceRuntimeTest {

  private final VmManager vmManager = mock(VmManager.class);
  private final VmWorkspaceRuntime runtime = new VmWorkspaceRuntime(vmManager);
  private final UUID workspace = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();

  @Test
  void startsAVmForTheWorkspaceAndItsOwnerAndReportsItsSize() {
    when(vmManager.create(workspace, owner, null)).thenReturn(vm(workspace, "PROVISIONING"));

    var size = runtime.start(workspace, owner, null, new WorkspaceEnvironment(Map.of(), Map.of()));

    assertThat(size).isEqualTo("cx23");
  }

  @Test
  void stopsAndRestartsThroughTheVmManager() {
    runtime.stop(workspace);
    runtime.restart(workspace);

    verify(vmManager).deleteFor(workspace);
    verify(vmManager).restartFor(workspace);
  }

  @Test
  void readsStatusesFromTheVmsAndFailsAWorkspaceWithNone() {
    var ready = UUID.randomUUID();
    var gone = UUID.randomUUID();
    when(vmManager.vms()).thenReturn(List.of(vm(workspace, "PROVISIONING"), vm(ready, "READY")));

    var statuses = runtime.statuses(List.of(workspace, ready, gone));

    assertThat(statuses)
        .containsEntry(workspace, WorkspaceStatus.INITIALIZING)
        .containsEntry(ready, WorkspaceStatus.READY)
        .containsEntry(gone, WorkspaceStatus.FAILED);
  }

  @Test
  @SuppressWarnings("unchecked")
  void passesEachVmChangeOnAsItsWorkspacesStatus() {
    doAnswer(
            i -> {
              var listener = (Consumer<ProvisionedVm>) i.getArgument(0);
              listener.accept(vm(workspace, "RESTARTING"));
              listener.accept(vm(workspace, "READY"));
              return null;
            })
        .when(vmManager)
        .watch(any());
    var heard = new ArrayList<WorkspaceStatus>();

    runtime.watch((id, status) -> heard.add(status));

    assertThat(heard).containsExactly(WorkspaceStatus.INITIALIZING, WorkspaceStatus.READY);
  }

  @Test
  void runsNoHarnessAndKeepsNoSecretYet() {
    runtime.putSecret(workspace, "KEY", "value");

    assertThat(runtime.harness(workspace)).isEmpty();
    assertThat(runtime.secret(workspace, "KEY")).isEmpty();
    assertThat(runtime.recentLog(workspace, 10)).isEmpty();
  }

  private static ProvisionedVm vm(UUID workspaceId, String status) {
    return new ProvisionedVm(UUID.randomUUID(), workspaceId, "cx23", status);
  }
}
