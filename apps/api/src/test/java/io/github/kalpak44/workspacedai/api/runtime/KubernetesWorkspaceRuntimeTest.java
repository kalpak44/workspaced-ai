package io.github.kalpak44.workspacedai.api.runtime;

import static io.github.kalpak44.workspacedai.api.runtime.KubernetesWorkspaceRuntime.WORKSPACE_ID_LABEL;
import static io.github.kalpak44.workspacedai.api.runtime.KubernetesWorkspaceRuntime.podName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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

  static final WorkspaceEnvironment ENVIRONMENT =
      new WorkspaceEnvironment(Map.of("BASE_URL", "https://x.test"), Map.of("API_KEY", "sk-1"));

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
    runtime =
        new KubernetesWorkspaceRuntime(
            client, new WorkspaceProperties("busybox:test", Duration.ofSeconds(5)));
    id = UUID.randomUUID();
  }

  @Test
  void startsALabelledLockedDownPodInItsOwnNamespace() {
    runtime.start(id, ENVIRONMENT);

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
    assertThat(container.getEnvFrom())
        .extracting(
            e -> e.getConfigMapRef() != null ? e.getConfigMapRef().getName() : null,
            e -> e.getSecretRef() != null ? e.getSecretRef().getName() : null)
        .containsExactly(tuple(podName(id), null), tuple(null, podName(id)));
  }

  @Test
  void keepsTheEnvironmentInALabelledSecretAndConfigMap() {
    runtime.start(id, ENVIRONMENT);

    var configMap = client.configMaps().inNamespace(client.getNamespace()).withName(podName(id));
    assertThat(configMap.get().getData())
        .containsExactlyEntriesOf(Map.of("BASE_URL", "https://x.test"));
    assertThat(configMap.get().getMetadata().getLabels())
        .containsEntry(WORKSPACE_ID_LABEL, id.toString());
    assertThat(secretValues()).containsExactlyEntriesOf(Map.of("API_KEY", "sk-1"));
    assertThat(secret().getMetadata().getLabels()).containsEntry(WORKSPACE_ID_LABEL, id.toString());
  }

  @Test
  void addsAndRemovesSecretsBesideTheProviderKey() {
    runtime.start(id, ENVIRONMENT);
    runtime.putSecret(id, "GITHUB_TOKEN", "ghp_1");
    assertThat(secretValues()).containsOnlyKeys("API_KEY", "GITHUB_TOKEN");
    runtime.putSecret(id, "GITHUB_TOKEN", "ghp_2");
    assertThat(secretValues()).containsEntry("GITHUB_TOKEN", "ghp_2");

    runtime.removeSecret(id, "GITHUB_TOKEN");
    runtime.removeSecret(id, "GITHUB_TOKEN");
    assertThat(secretValues()).containsOnlyKeys("API_KEY");
  }

  @Test
  void createsTheSecretForAWorkspaceThatHadNone() {
    runtime.removeSecret(id, "GITHUB_TOKEN");
    runtime.putSecret(id, "GITHUB_TOKEN", "ghp_1");
    assertThat(secretValues()).containsExactlyEntriesOf(Map.of("GITHUB_TOKEN", "ghp_1"));
    assertThat(secret().getMetadata().getLabels()).containsEntry(WORKSPACE_ID_LABEL, id.toString());
  }

  @Test
  void leavesNothingBehindWhenThePodCannotBeCreated() {
    // A pod of this name already exists, so creating the workspace's pod fails.
    client
        .pods()
        .inNamespace(client.getNamespace())
        .resource(new PodBuilder().withNewMetadata().withName(podName(id)).endMetadata().build())
        .create();
    assertThatThrownBy(() -> runtime.start(id, ENVIRONMENT))
        .isInstanceOf(KubernetesClientException.class);
    assertThat(secret()).isNull();
    assertThat(client.configMaps().inNamespace(client.getNamespace()).withName(podName(id)).get())
        .isNull();
  }

  @Test
  void reportsAFreshPodAsInitializing() {
    runtime.start(id, ENVIRONMENT);
    assertThat(runtime.statuses(List.of(id))).containsEntry(id, WorkspaceStatus.INITIALIZING);
  }

  @Test
  void reportsAReadyPodAsReady() {
    runtime.start(id, ENVIRONMENT);
    setPhase("Running", "True");
    assertThat(runtime.statuses(List.of(id))).containsEntry(id, WorkspaceStatus.READY);
  }

  @Test
  void reportsARunningButUnreadyPodAsInitializing() {
    runtime.start(id, ENVIRONMENT);
    setPhase("Running", "False");
    assertThat(runtime.statuses(List.of(id))).containsEntry(id, WorkspaceStatus.INITIALIZING);
  }

  @Test
  void reportsAStoppedOrMissingPodAsFailed() {
    runtime.start(id, ENVIRONMENT);
    setPhase("Failed", "False");
    var missing = UUID.randomUUID();
    assertThat(runtime.statuses(List.of(id, missing)))
        .containsEntry(id, WorkspaceStatus.FAILED)
        .containsEntry(missing, WorkspaceStatus.FAILED);
  }

  @Test
  void stopsThePodAndToleratesOneAlreadyGone() {
    runtime.start(id, ENVIRONMENT);
    // Returns only once the pod is gone, which is what lets a delete report it honestly.
    runtime.stop(id);
    assertThat(client.pods().inNamespace(client.getNamespace()).withName(podName(id)).get())
        .isNull();
    assertThat(secret()).isNull();
    assertThat(client.configMaps().inNamespace(client.getNamespace()).withName(podName(id)).get())
        .isNull();
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

    runtime.start(id, ENVIRONMENT);
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
    runtime.start(id, ENVIRONMENT);
    assertThat(second.poll(5, TimeUnit.SECONDS)).isEqualTo(id);
    assertThat(first).isEmpty();
  }

  @Test
  void refusesToStartWithoutKnowingItsNamespace() {
    var noNamespace = mock(KubernetesClient.class);
    var properties = new WorkspaceProperties("busybox:test", Duration.ofSeconds(5));
    assertThatThrownBy(() -> new KubernetesWorkspaceRuntime(noNamespace, properties))
        .isInstanceOf(IllegalStateException.class);
  }

  private Secret secret() {
    return client.secrets().inNamespace(client.getNamespace()).withName(podName(id)).get();
  }

  private Map<String, String> secretValues() {
    var values = new HashMap<String, String>();
    secret()
        .getData()
        .forEach(
            (k, v) ->
                values.put(k, new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8)));
    return values;
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
