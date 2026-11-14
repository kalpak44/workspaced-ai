package io.github.kalpak44.workspacedai.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.compute.ProvisionedVm;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.compute.VmManagerUnavailableException;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class VmWorkspaceRuntimeTest {

  private final VmManager vmManager = mock(VmManager.class);
  private final VmWorkspaceRuntime runtime =
      new VmWorkspaceRuntime(
          vmManager,
          new AgentHubProperties(URI.create("http://workspaced-ai-agent-hub:8080/api/v1/")));
  private final UUID workspace = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();

  @Test
  void startsAVmWithTheProvidersKeyApartFromTheRestOfTheEnvironment() {
    when(vmManager.create(
            workspace,
            owner,
            null,
            "sk-or-v1-test",
            Map.of("OPENROUTER_BASE_URL", "https://or", "OPENCODE_PASSWORD", "p")))
        .thenReturn(vm(workspace, "PROVISIONING"));

    var size =
        runtime.start(
            workspace,
            owner,
            null,
            new WorkspaceEnvironment(
                Map.of("OPENROUTER_BASE_URL", "https://or"),
                Map.of("OPENROUTER_API_KEY", "sk-or-v1-test", "OPENCODE_PASSWORD", "p")));

    assertThat(size).isEqualTo("cx23");
  }

  @Test
  void printsNoSecretValue() {
    var environment =
        new WorkspaceEnvironment(
            Map.of("OPENROUTER_BASE_URL", "https://or"), Map.of("OPENROUTER_API_KEY", "sk-secret"));

    assertThat(environment.toString())
        .contains("OPENROUTER_API_KEY", "https://or")
        .doesNotContain("sk-secret");
  }

  @Test
  void keepsSecretsThroughTheVmManagerAndReadsNoneBack() {
    runtime.putSecret(workspace, "GITHUB_TOKEN", "ghp_x");
    runtime.removeSecret(workspace, "GITHUB_TOKEN");

    verify(vmManager).putSecret(workspace, "GITHUB_TOKEN", "ghp_x");
    verify(vmManager).removeSecret(workspace, "GITHUB_TOKEN");
    assertThat(runtime.secret(workspace, "GITHUB_TOKEN")).isEmpty();
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
  void reachesTheHarnessThroughTheAgentHubsTunnelAskingForTheVmOnce() {
    var vm = UUID.randomUUID();
    when(vmManager.vmIdFor(workspace)).thenReturn(Optional.of(vm));

    var first = runtime.harness(workspace).orElseThrow();
    runtime.harness(workspace);

    assertThat(first.baseUrl())
        .isEqualTo(
            URI.create(
                "http://workspaced-ai-agent-hub:8080/api/v1/internal/vms/" + vm + "/harness"));
    assertThat(first.password()).isEmpty();
    verify(vmManager, times(1)).vmIdFor(workspace);
  }

  @Test
  void forgetsTheVmOnceTheWorkspaceStops() {
    when(vmManager.vmIdFor(workspace)).thenReturn(Optional.of(UUID.randomUUID()));
    runtime.harness(workspace);

    runtime.stop(workspace);
    runtime.harness(workspace);

    verify(vmManager, times(2)).vmIdFor(workspace);
  }

  @Test
  void hasNoHarnessWithoutAVmOrAVmManagerOrAnAgentHub() {
    var other = UUID.randomUUID();
    when(vmManager.vmIdFor(workspace)).thenReturn(Optional.empty());
    when(vmManager.vmIdFor(other)).thenThrow(new VmManagerUnavailableException("down", null));
    when(vmManager.vmIdFor(owner)).thenReturn(Optional.of(UUID.randomUUID()));

    assertThat(runtime.harness(workspace)).isEmpty();
    assertThat(runtime.harness(other)).isEmpty();
    assertThat(new VmWorkspaceRuntime(vmManager, new AgentHubProperties(null)).harness(owner))
        .isEmpty();
  }

  @Test
  void keepsNoSecretValueAndNoLog() {
    runtime.putSecret(workspace, "KEY", "value");

    assertThat(runtime.secret(workspace, "KEY")).isEmpty();
    assertThat(runtime.recentLog(workspace, 10)).isEmpty();
  }

  private static ProvisionedVm vm(UUID workspaceId, String status) {
    return new ProvisionedVm(UUID.randomUUID(), workspaceId, "cx23", status);
  }
}
