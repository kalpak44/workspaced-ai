package io.github.kalpak44.workspacedai.api.runtime;

import static io.github.kalpak44.workspacedai.api.runtime.KubernetesWorkspaceRuntime.MANAGED_BY;
import static io.github.kalpak44.workspacedai.api.runtime.KubernetesWorkspaceRuntime.MANAGED_BY_LABEL;
import static io.github.kalpak44.workspacedai.api.runtime.KubernetesWorkspaceRuntime.WORKSPACE_ID_LABEL;
import static io.github.kalpak44.workspacedai.api.runtime.KubernetesWorkspaceRuntime.resourceName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
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

/**
 * A fresh API server per test, so nothing leaks from one test into the next. It runs no
 * controllers, so the pods a Deployment would bring up are created here by hand.
 */
class KubernetesWorkspaceRuntimeTest {

  static final WorkspaceProperties PROPERTIES =
      new WorkspaceProperties(
          "harness:test",
          Duration.ofSeconds(5),
          "10Gi",
          "fast",
          new WorkspaceProperties.LocalHarness(null, null));

  static final WorkspaceEnvironment ENVIRONMENT =
      new WorkspaceEnvironment(
          Map.of("BASE_URL", "https://x.test"),
          Map.of("API_KEY", "sk-1", "OPENCODE_PASSWORD", "pw-1"));

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
    runtime = new KubernetesWorkspaceRuntime(client, PROPERTIES);
    id = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    runtime.close();
    client.close();
    server.destroy();
  }

  @Test
  void startsOneLockedDownReplicaBehindAService() {
    runtime.start(id, ENVIRONMENT);

    var deployment =
        client.apps().deployments().inNamespace(namespace()).withName(resourceName(id)).get();
    assertThat(deployment.getMetadata().getName()).isEqualTo("workspaced-ai-workspace-" + id);
    assertThat(deployment.getMetadata().getLabels())
        .containsEntry(WORKSPACE_ID_LABEL, id.toString())
        .containsEntry("app.kubernetes.io/part-of", "workspaced-ai")
        .containsEntry(MANAGED_BY_LABEL, MANAGED_BY);
    assertThat(deployment.getSpec().getReplicas()).isEqualTo(1);
    // One home per workspace: a rolling update would briefly run two.
    assertThat(deployment.getSpec().getStrategy().getType()).isEqualTo("Recreate");
    assertThat(deployment.getSpec().getSelector().getMatchLabels())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(WORKSPACE_ID_LABEL, id.toString(), MANAGED_BY_LABEL, MANAGED_BY));
    assertThat(deployment.getSpec().getTemplate().getMetadata().getLabels())
        .containsAllEntriesOf(deployment.getSpec().getSelector().getMatchLabels());

    var spec = deployment.getSpec().getTemplate().getSpec();
    assertThat(spec.getAutomountServiceAccountToken()).isFalse();
    assertThat(spec.getSecurityContext().getRunAsNonRoot()).isTrue();
    assertThat(spec.getSecurityContext().getRunAsUser()).isEqualTo(1000L);
    assertThat(spec.getSecurityContext().getFsGroup()).isEqualTo(1000L);
    var container = spec.getContainers().getFirst();
    assertThat(container.getImage()).isEqualTo("harness:test");
    assertThat(container.getSecurityContext().getReadOnlyRootFilesystem()).isTrue();
    assertThat(container.getSecurityContext().getCapabilities().getDrop()).containsExactly("ALL");
    assertThat(container.getResources().getLimits()).containsKeys("cpu", "memory");
    assertThat(container.getPorts().getFirst().getContainerPort()).isEqualTo(4096);
    assertThat(container.getReadinessProbe().getTcpSocket().getPort().getIntVal()).isEqualTo(4096);
    // The root is read-only, so the home and /tmp are where the agent writes.
    assertThat(container.getVolumeMounts())
        .extracting(m -> m.getMountPath())
        .containsExactlyInAnyOrder("/home/workspace", "/tmp");
    assertThat(spec.getVolumes())
        .anySatisfy(
            v ->
                assertThat(v.getPersistentVolumeClaim().getClaimName()).isEqualTo(resourceName(id)))
        .anySatisfy(v -> assertThat(v.getEmptyDir()).isNotNull());
    var claim =
        client.persistentVolumeClaims().inNamespace(namespace()).withName(resourceName(id)).get();
    assertThat(claim.getSpec().getStorageClassName()).isEqualTo("fast");
    assertThat(claim.getSpec().getAccessModes()).containsExactly("ReadWriteOnce");
    assertThat(claim.getSpec().getResources().getRequests().get("storage").getAmount())
        .isEqualTo("10");
    assertThat(container.getEnvFrom())
        .extracting(
            e -> e.getConfigMapRef() != null ? e.getConfigMapRef().getName() : null,
            e -> e.getSecretRef() != null ? e.getSecretRef().getName() : null)
        .containsExactly(tuple(resourceName(id), null), tuple(null, resourceName(id)));

    var service = client.services().inNamespace(namespace()).withName(resourceName(id)).get();
    assertThat(service.getSpec().getSelector())
        .containsExactlyInAnyOrderEntriesOf(deployment.getSpec().getSelector().getMatchLabels());
    var port = service.getSpec().getPorts().getFirst();
    assertThat(port.getPort()).isEqualTo(4096);
    assertThat(port.getTargetPort().getStrVal()).isEqualTo("harness");
  }

  @Test
  void keepsTheEnvironmentInALabelledSecretAndConfigMap() {
    runtime.start(id, ENVIRONMENT);

    var configMap = client.configMaps().inNamespace(namespace()).withName(resourceName(id)).get();
    assertThat(configMap.getData()).containsExactlyEntriesOf(Map.of("BASE_URL", "https://x.test"));
    assertThat(configMap.getMetadata().getLabels())
        .containsEntry(WORKSPACE_ID_LABEL, id.toString());
    assertThat(secretValues())
        .containsExactlyInAnyOrderEntriesOf(Map.of("API_KEY", "sk-1", "OPENCODE_PASSWORD", "pw-1"));
    assertThat(secret().getMetadata().getLabels()).containsEntry(WORKSPACE_ID_LABEL, id.toString());
  }

  @Test
  void addsAndRemovesSecretsBesideTheProviderKey() {
    runtime.start(id, ENVIRONMENT);
    runtime.putSecret(id, "GITHUB_TOKEN", "ghp_1");
    assertThat(secretValues()).containsOnlyKeys("API_KEY", "OPENCODE_PASSWORD", "GITHUB_TOKEN");
    runtime.putSecret(id, "GITHUB_TOKEN", "ghp_2");
    assertThat(secretValues()).containsEntry("GITHUB_TOKEN", "ghp_2");

    runtime.removeSecret(id, "GITHUB_TOKEN");
    runtime.removeSecret(id, "GITHUB_TOKEN");
    assertThat(secretValues()).containsOnlyKeys("API_KEY", "OPENCODE_PASSWORD");
  }

  @Test
  void readsOneSecretBack() {
    assertThat(runtime.secret(id, "API_KEY")).isEmpty();
    runtime.start(id, ENVIRONMENT);
    runtime.putSecret(id, "GITHUB_TOKEN", "ghp_1");
    assertThat(runtime.secret(id, "GITHUB_TOKEN")).hasValue("ghp_1");
    assertThat(runtime.secret(id, "MISSING")).isEmpty();
  }

  @Test
  void createsTheSecretForAWorkspaceThatHadNone() {
    runtime.removeSecret(id, "GITHUB_TOKEN");
    runtime.putSecret(id, "GITHUB_TOKEN", "ghp_1");
    assertThat(secretValues()).containsExactlyEntriesOf(Map.of("GITHUB_TOKEN", "ghp_1"));
    assertThat(secret().getMetadata().getLabels()).containsEntry(WORKSPACE_ID_LABEL, id.toString());
  }

  @Test
  void reachesTheHarnessThroughItsServiceByItsFullName() {
    assertThat(runtime.harness(id)).isEmpty();
    runtime.start(id, ENVIRONMENT);
    assertThat(runtime.harness(id))
        .hasValueSatisfying(
            endpoint -> {
              assertThat(endpoint.baseUrl())
                  .hasToString(
                      "http://workspaced-ai-workspace-" + id + "." + namespace() + ".svc:4096");
              assertThat(endpoint.password()).isEqualTo("pw-1");
            });

    // A workspace made before the harness has no password, so the API cannot sign in.
    runtime.removeSecret(id, "OPENCODE_PASSWORD");
    assertThat(runtime.harness(id)).isEmpty();
    runtime.putSecret(id, "OPENCODE_PASSWORD", "pw-1");
    client.services().inNamespace(namespace()).withName(resourceName(id)).delete();
    assertThat(runtime.harness(id)).isEmpty();
  }

  @Test
  void leavesNothingBehindWhenStartingFailsPartWay() {
    // A Service of this name already exists, so the last create fails.
    client
        .services()
        .inNamespace(namespace())
        .resource(
            new ServiceBuilder().withNewMetadata().withName(resourceName(id)).endMetadata().build())
        .create();
    assertThatThrownBy(() -> runtime.start(id, ENVIRONMENT))
        .isInstanceOf(KubernetesClientException.class);
    assertThat(secret()).isNull();
    assertThat(client.configMaps().inNamespace(namespace()).withName(resourceName(id)).get())
        .isNull();
    assertThat(
            client
                .persistentVolumeClaims()
                .inNamespace(namespace())
                .withName(resourceName(id))
                .get())
        .isNull();
    assertThat(
            client.apps().deployments().inNamespace(namespace()).withName(resourceName(id)).get())
        .isNull();
  }

  @Test
  void isStartingUntilAReplicaIsReady() {
    runtime.start(id, ENVIRONMENT);
    // The Deployment exists but has no pod yet; asked at once, before the cache has it.
    assertThat(runtime.statuses(List.of(id))).containsEntry(id, WorkspaceStatus.INITIALIZING);
    replica("a", "Running", "False");
    awaitStatusOf(id, WorkspaceStatus.INITIALIZING);
    replica("a", "Running", "True");
    awaitStatusOf(id, WorkspaceStatus.READY);
  }

  @Test
  void isStartingAgainWhileTheDeploymentReplacesAPodThatFailed() {
    runtime.start(id, ENVIRONMENT);
    replica("a", "Failed", "False");
    awaitStatusOf(id, WorkspaceStatus.INITIALIZING);
    // A ready replica wins over one that is on its way out.
    replica("b", "Running", "True");
    awaitStatusOf(id, WorkspaceStatus.READY);
  }

  @Test
  void judgesAWorkspaceWithNoDeploymentByItsBarePodOrFailsIt() {
    var missing = UUID.randomUUID();
    bare("Running", "True");
    awaitStatusOf(id, WorkspaceStatus.READY);
    assertThat(runtime.statuses(List.of(missing))).containsEntry(missing, WorkspaceStatus.FAILED);
    bare("Failed", "False");
    awaitStatusOf(id, WorkspaceStatus.FAILED);
  }

  @Test
  void answersStatusesFromItsCacheWithoutAskingTheApiServer() {
    runtime.start(id, ENVIRONMENT);
    replica("a", "Running", "True");
    awaitStatusOf(id, WorkspaceStatus.READY);
    var others = new java.util.ArrayList<UUID>();
    for (var i = 0; i < 20; i++) {
      var other = UUID.randomUUID();
      runtime.start(other, ENVIRONMENT);
      others.add(other);
    }
    for (var other : others) {
      awaitStatusOf(other, WorkspaceStatus.INITIALIZING);
    }
    var all = new java.util.ArrayList<>(others);
    all.add(id);

    var before = server.getRequestCount();
    for (var i = 0; i < 10; i++) {
      assertThat(runtime.statuses(all)).hasSize(21).containsEntry(id, WorkspaceStatus.READY);
    }
    assertThat(server.getRequestCount()).isEqualTo(before);
  }

  /** The cache follows the API server a moment behind, so a status is waited for, not read once. */
  private void awaitStatusOf(UUID workspace, WorkspaceStatus expected) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (runtime.statuses(List.of(workspace)).get(workspace) != expected) {
      assertThat(System.nanoTime()).as("status of %s", workspace).isLessThan(deadline);
      java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
    }
  }

  @Test
  void stopsEverythingAndToleratesWhatIsAlreadyGone() {
    runtime.start(id, ENVIRONMENT);
    runtime.stop(id);
    assertThat(
            client.apps().deployments().inNamespace(namespace()).withName(resourceName(id)).get())
        .isNull();
    assertThat(client.services().inNamespace(namespace()).withName(resourceName(id)).get())
        .isNull();
    assertThat(secret()).isNull();
    assertThat(client.configMaps().inNamespace(namespace()).withName(resourceName(id)).get())
        .isNull();
    runtime.stop(id);
  }

  @Test
  void restartsByDeletingThePodsAndKeepingEverythingElse() {
    runtime.start(id, ENVIRONMENT);
    replica("a", "Running", "True");
    runtime.restart(id);
    assertThat(client.pods().inNamespace(namespace()).withName(resourceName(id) + "-a").get())
        .isNull();
    assertThat(
            client.apps().deployments().inNamespace(namespace()).withName(resourceName(id)).get())
        .isNotNull();
    assertThat(secret()).isNotNull();
    assertThat(
            client
                .persistentVolumeClaims()
                .inNamespace(namespace())
                .withName(resourceName(id))
                .get())
        .isNotNull();
  }

  @Test
  void reportsAPodThatIsShuttingDownAsStarting() {
    var terminating =
        new PodBuilder()
            .withNewMetadata()
            .withName(resourceName(id) + "-a")
            .withDeletionTimestamp("2026-11-06T12:00:00Z")
            .endMetadata()
            .withNewStatus()
            .withPhase("Running")
            .addToConditions(new PodConditionBuilder().withType("Ready").withStatus("True").build())
            .endStatus()
            .build();
    assertThat(KubernetesWorkspaceRuntime.statusOf(terminating))
        .isEqualTo(WorkspaceStatus.INITIALIZING);
  }

  @Test
  void movesAWorkspaceOnAnOlderHarnessToTheCurrentOneOnRestart() {
    var older =
        new KubernetesWorkspaceRuntime(
            client,
            new WorkspaceProperties(
                "harness:old", Duration.ofSeconds(5), "10Gi", "fast", PROPERTIES.localHarness()));
    older.start(id, ENVIRONMENT);
    older.close();
    replica("a", "Running", "True");

    runtime.restart(id);

    var deployment =
        client.apps().deployments().inNamespace(namespace()).withName(resourceName(id)).get();
    assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().getFirst().getImage())
        .isEqualTo("harness:test");
    // The Deployment replaces the pod itself; nothing else of the workspace is touched.
    assertThat(client.pods().inNamespace(namespace()).withName(resourceName(id) + "-a").get())
        .isNotNull();
    assertThat(client.secrets().inNamespace(namespace()).withName(resourceName(id)).get())
        .isNotNull();
  }

  @Test
  void refusesToRestartTheBarePodAWorkspaceRanAsBeforeDeployments() {
    bare("Running", "True");
    assertThatThrownBy(() -> runtime.restart(id)).isInstanceOf(IllegalStateException.class);
    assertThat(client.pods().inNamespace(namespace()).withName(resourceName(id)).get()).isNotNull();
  }

  @Test
  void stopsTheBarePodAWorkspaceRanAsBeforeDeployments() {
    bare("Running", "True");
    runtime.stop(id);
    assertThat(client.pods().inNamespace(namespace()).withName(resourceName(id)).get()).isNull();
  }

  @Test
  void failsAStopWhosePodsOutlastTheTimeout() {
    var impatient =
        new KubernetesWorkspaceRuntime(
            client,
            new WorkspaceProperties(
                "harness:test",
                Duration.ofMillis(300),
                "1Gi",
                "",
                new WorkspaceProperties.LocalHarness(null, null)));
    runtime.start(id, ENVIRONMENT);
    // Nothing here deletes a Deployment's pods, as a stuck node would not.
    replica("a", "Running", "True");
    assertThatThrownBy(() -> impatient.stop(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not stop in time");
    impatient.close();
  }

  @Test
  void reportsEachChangeAndKeepsAReplacedPodStarting() throws Exception {
    var changes = new LinkedBlockingQueue<Map.Entry<UUID, WorkspaceStatus>>();
    runtime.watch((workspace, status) -> changes.add(Map.entry(workspace, status)));
    runtime.start(id, ENVIRONMENT);

    replica("a", "Pending", "False");
    awaitStatus(changes, WorkspaceStatus.INITIALIZING);
    replica("a", "Running", "True");
    awaitStatus(changes, WorkspaceStatus.READY);

    // The pod goes, but its Deployment brings up another: starting, not failed.
    client.pods().inNamespace(namespace()).withName(resourceName(id) + "-a").delete();
    assertThat(changes.poll(5, TimeUnit.SECONDS))
        .isEqualTo(Map.entry(id, WorkspaceStatus.INITIALIZING));
  }

  /** A status may repeat, so this skips repeats of the one before until the expected one. */
  private void awaitStatus(
      LinkedBlockingQueue<Map.Entry<UUID, WorkspaceStatus>> changes, WorkspaceStatus expected)
      throws InterruptedException {
    Map.Entry<UUID, WorkspaceStatus> change;
    do {
      change = changes.poll(5, TimeUnit.SECONDS);
      assertThat(change).isNotNull();
    } while (change.getValue() != expected);
    assertThat(change.getKey()).isEqualTo(id);
  }

  @Test
  void replacesAnEarlierWatch() throws Exception {
    var first = new LinkedBlockingQueue<UUID>();
    var second = new LinkedBlockingQueue<UUID>();
    runtime.watch((workspace, status) -> first.add(workspace));
    runtime.watch((workspace, status) -> second.add(workspace));
    bare("Pending", "False");
    assertThat(second.poll(5, TimeUnit.SECONDS)).isEqualTo(id);
    assertThat(first).isEmpty();
  }

  @Test
  void refusesToStartWithoutKnowingItsNamespace() {
    var noNamespace = mock(KubernetesClient.class);
    assertThatThrownBy(() -> new KubernetesWorkspaceRuntime(noNamespace, PROPERTIES))
        .isInstanceOf(IllegalStateException.class);
  }

  private String namespace() {
    return client.getNamespace();
  }

  private Secret secret() {
    return client.secrets().inNamespace(namespace()).withName(resourceName(id)).get();
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

  /** A pod as the workspace's Deployment would make it, under a generated name. */
  private void replica(String suffix, String phase, String ready) {
    put(resourceName(id) + "-" + suffix, phase, ready);
  }

  /** The bare pod a workspace ran as before Deployments, named for the workspace itself. */
  private void bare(String phase, String ready) {
    put(resourceName(id), phase, ready);
  }

  private void put(String name, String phase, String ready) {
    var pods = client.pods().inNamespace(namespace());
    if (pods.withName(name).get() == null) {
      pods.resource(
              new PodBuilder()
                  .withNewMetadata()
                  .withName(name)
                  .withLabels(
                      Map.of(WORKSPACE_ID_LABEL, id.toString(), MANAGED_BY_LABEL, MANAGED_BY))
                  .endMetadata()
                  .build())
          .create();
    }
    pods.resource(
            new PodBuilder(pods.withName(name).get())
                .withNewStatus()
                .withPhase(phase)
                .withConditions(
                    new PodConditionBuilder().withType("Ready").withStatus(ready).build())
                .endStatus()
                .build())
        .updateStatus();
  }
}
