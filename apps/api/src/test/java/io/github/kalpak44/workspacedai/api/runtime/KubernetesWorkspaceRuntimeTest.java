package io.github.kalpak44.workspacedai.api.runtime;

import static io.github.kalpak44.workspacedai.api.runtime.KubernetesWorkspaceRuntime.WORKSPACE_ID_LABEL;
import static io.github.kalpak44.workspacedai.api.runtime.KubernetesWorkspaceRuntime.podName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A fresh API server per test, so no pod or open watch leaks from one test into the next. */
class KubernetesWorkspaceRuntimeTest {

  KubernetesMockServer server;
  KubernetesClient client;
  KubernetesWorkspaceRuntime runtime;
  UUID id;

  @BeforeEach
  void setUp() {
    server =
        new KubernetesMockServer(
            new Context(),
            new MockWebServer(),
            new HashMap<>(),
            new KubernetesCrudDispatcher(),
            false);
    server.init();
    client = server.createClient();
    runtime = new KubernetesWorkspaceRuntime(client, new WorkspaceProperties("busybox:test"));
    id = UUID.randomUUID();
  }

  @Test
  void startsALabelledLockedDownPodInItsOwnNamespace() {
    runtime.start(id);

    var pod = client.pods().inNamespace(client.getNamespace()).withName(podName(id)).get();
    assertThat(pod.getMetadata().getName()).isEqualTo("workspaced-ai-workspace-" + id);
    assertThat(pod.getMetadata().getLabels())
        .containsEntry(WORKSPACE_ID_LABEL, id.toString())
        .containsEntry("app.kubernetes.io/part-of", "workspaced-ai")
        .containsEntry("app.kubernetes.io/managed-by", "workspaced-ai-api");
    var spec = pod.getSpec();
    assertThat(spec.getAutomountServiceAccountToken()).isFalse();
    assertThat(spec.getSecurityContext().getRunAsNonRoot()).isTrue();
    var container = spec.getContainers().getFirst();
    assertThat(container.getImage()).isEqualTo("busybox:test");
    assertThat(container.getSecurityContext().getReadOnlyRootFilesystem()).isTrue();
    assertThat(container.getSecurityContext().getCapabilities().getDrop()).containsExactly("ALL");
    assertThat(container.getResources().getLimits()).containsKeys("cpu", "memory");
  }

  @Test
  void reportsAFreshPodAsInitializing() {
    runtime.start(id);
    assertThat(runtime.statuses(List.of(id))).containsEntry(id, WorkspaceStatus.INITIALIZING);
  }

  @Test
  void reportsAReadyPodAsReady() {
    runtime.start(id);
    setPhase("Running", "True");
    assertThat(runtime.statuses(List.of(id))).containsEntry(id, WorkspaceStatus.READY);
  }

  @Test
  void reportsARunningButUnreadyPodAsInitializing() {
    runtime.start(id);
    setPhase("Running", "False");
    assertThat(runtime.statuses(List.of(id))).containsEntry(id, WorkspaceStatus.INITIALIZING);
  }

  @Test
  void reportsAStoppedOrMissingPodAsFailed() {
    runtime.start(id);
    setPhase("Failed", "False");
    var missing = UUID.randomUUID();
    assertThat(runtime.statuses(List.of(id, missing)))
        .containsEntry(id, WorkspaceStatus.FAILED)
        .containsEntry(missing, WorkspaceStatus.FAILED);
  }

  @Test
  void stopsThePodAndToleratesOneAlreadyGone() {
    runtime.start(id);
    runtime.stop(id);
    runtime.stop(id);
    assertThat(client.pods().inNamespace(client.getNamespace()).withName(podName(id)).get())
        .isNull();
  }

  @AfterEach
  void tearDown() {
    runtime.close();
    client.close();
    server.destroy();
  }

  @Test
  void reportsEachChangeAsItHappensUntilThePodIsGone() throws Exception {
    var changes = new LinkedBlockingQueue<Map.Entry<UUID, WorkspaceStatus>>();
    runtime.watch((workspace, status) -> changes.add(Map.entry(workspace, status)));

    runtime.start(id);
    assertThat(changes.poll(5, TimeUnit.SECONDS))
        .isEqualTo(Map.entry(id, WorkspaceStatus.INITIALIZING));

    setPhase("Running", "True");
    assertThat(changes.poll(5, TimeUnit.SECONDS)).isEqualTo(Map.entry(id, WorkspaceStatus.READY));

    runtime.stop(id);
    assertThat(changes.poll(5, TimeUnit.SECONDS)).isEqualTo(Map.entry(id, WorkspaceStatus.FAILED));
  }

  @Test
  void replacesAnEarlierWatch() throws Exception {
    var first = new LinkedBlockingQueue<UUID>();
    var second = new LinkedBlockingQueue<UUID>();
    runtime.watch((workspace, status) -> first.add(workspace));
    runtime.watch((workspace, status) -> second.add(workspace));
    runtime.start(id);
    assertThat(second.poll(5, TimeUnit.SECONDS)).isEqualTo(id);
    assertThat(first).isEmpty();
  }

  @Test
  void refusesToStartWithoutKnowingItsNamespace() {
    var noNamespace = mock(KubernetesClient.class);
    var properties = new WorkspaceProperties("busybox:test");
    assertThatThrownBy(() -> new KubernetesWorkspaceRuntime(noNamespace, properties))
        .isInstanceOf(IllegalStateException.class);
  }

  private void setPhase(String phase, String ready) {
    var pods = client.pods().inNamespace(client.getNamespace());
    var pod = pods.withName(podName(id)).get();
    pods.resource(
            new PodBuilder(pod)
                .withNewStatus()
                .withPhase(phase)
                .withConditions(
                    new PodConditionBuilder().withType("Ready").withStatus(ready).build())
                .endStatus()
                .build())
        .updateStatus();
  }
}
