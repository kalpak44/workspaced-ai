package io.github.kalpak44.workspacedai.api.runtime;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One pod per workspace, in the namespace the API itself runs in, with a Secret and a ConfigMap of
 * the same name that it takes its environment from.
 */
public class KubernetesWorkspaceRuntime implements WorkspaceRuntime, AutoCloseable {

  static final String WORKSPACE_ID_LABEL = "workspaced-ai/workspace-id";
  static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
  static final String MANAGED_BY = "workspaced-ai-api";
  static final String POD_PREFIX = "workspaced-ai-workspace-";
  static final int HARNESS_PORT = 4096;
  // The user the harness image runs as, which owns the home it works in.
  private static final long HARNESS_USER = 1000L;

  private final KubernetesClient client;
  private final String namespace;
  private final String image;
  private final long stopTimeoutMillis;
  private SharedIndexInformer<Pod> informer;

  public KubernetesWorkspaceRuntime(KubernetesClient client, WorkspaceProperties properties) {
    this.client = client;
    this.namespace = client.getNamespace();
    this.image = properties.image();
    this.stopTimeoutMillis = properties.stopTimeout().toMillis();
    if (namespace == null) {
      throw new IllegalStateException("cannot tell which namespace the API runs in");
    }
  }

  /** The pod, its Secret and its ConfigMap all share this name. */
  static String podName(UUID workspaceId) {
    return POD_PREFIX + workspaceId;
  }

  /**
   * The Secret and ConfigMap go first, since the pod cannot start without them. A failure part way
   * removes what was made, because the caller rolls the workspace back and nothing else would.
   */
  @Override
  public void start(UUID workspaceId, WorkspaceEnvironment environment) {
    try {
      client
          .configMaps()
          .inNamespace(namespace)
          .resource(
              new ConfigMapBuilder()
                  .withMetadata(metadata(workspaceId))
                  .withData(environment.config())
                  .build())
          .create();
      client
          .secrets()
          .inNamespace(namespace)
          .resource(
              new SecretBuilder()
                  .withMetadata(metadata(workspaceId))
                  .withType("Opaque")
                  .withData(encode(environment.secrets()))
                  .build())
          .create();
      client.pods().inNamespace(namespace).resource(pod(workspaceId)).create();
    } catch (RuntimeException e) {
      removeEnvironment(workspaceId);
      throw e;
    }
  }

  @Override
  public void stop(UUID workspaceId) {
    var pod = client.pods().inNamespace(namespace).withName(podName(workspaceId));
    pod.delete();
    // A delete only marks the pod terminating; this waits for the API server to drop it.
    pod.waitUntilCondition(Objects::isNull, stopTimeoutMillis, TimeUnit.MILLISECONDS);
    removeEnvironment(workspaceId);
  }

  /** Creates the Secret when it is missing, as it is for a workspace made before secrets were. */
  @Override
  public void putSecret(UUID workspaceId, String name, String value) {
    var secret = client.secrets().inNamespace(namespace).withName(podName(workspaceId));
    var encoded = encode(Map.of(name, value));
    if (secret.get() == null) {
      secret.create(
          new SecretBuilder()
              .withMetadata(metadata(workspaceId))
              .withType("Opaque")
              .withData(encoded)
              .build());
    } else {
      secret.edit(s -> new SecretBuilder(s).addToData(encoded).build());
    }
  }

  @Override
  public void removeSecret(UUID workspaceId, String name) {
    var secret = client.secrets().inNamespace(namespace).withName(podName(workspaceId));
    var current = secret.get();
    if (current != null && current.getData() != null && current.getData().containsKey(name)) {
      secret.edit(s -> new SecretBuilder(s).removeFromData(name).build());
    }
  }

  /**
   * Straight to the pod's IP rather than through a Service per workspace: the pod is the only thing
   * that could answer, and the password in its Secret is what keeps others out.
   */
  @Override
  public Optional<HarnessEndpoint> harness(UUID workspaceId) {
    var pod = client.pods().inNamespace(namespace).withName(podName(workspaceId)).get();
    var ip = pod == null || pod.getStatus() == null ? null : pod.getStatus().getPodIP();
    var secret = client.secrets().inNamespace(namespace).withName(podName(workspaceId)).get();
    var data = secret == null ? null : secret.getData();
    if (ip == null || data == null || !data.containsKey(Harness.PASSWORD_VARIABLE)) {
      return Optional.empty();
    }
    var password =
        new String(
            Base64.getDecoder().decode(data.get(Harness.PASSWORD_VARIABLE)),
            StandardCharsets.UTF_8);
    return Optional.of(
        new HarnessEndpoint(URI.create("http://" + ip + ":" + HARNESS_PORT), password));
  }

  @Override
  public Map<UUID, WorkspaceStatus> statuses(Collection<UUID> workspaceIds) {
    Map<String, Pod> pods =
        client
            .pods()
            .inNamespace(namespace)
            .withLabel(MANAGED_BY_LABEL, MANAGED_BY)
            .list()
            .getItems()
            .stream()
            .collect(
                Collectors.toMap(
                    p -> p.getMetadata().getLabels().get(WORKSPACE_ID_LABEL),
                    Function.identity(),
                    (a, b) -> a));
    var statuses = new HashMap<UUID, WorkspaceStatus>();
    for (var id : workspaceIds) {
      var pod = pods.get(id.toString());
      statuses.put(id, pod == null ? WorkspaceStatus.FAILED : statusOf(pod));
    }
    return statuses;
  }

  /** An informer rather than polling: the API server pushes each pod change as it happens. */
  @Override
  public synchronized void watch(BiConsumer<UUID, WorkspaceStatus> listener) {
    if (informer != null) {
      informer.close();
    }
    informer =
        client
            .pods()
            .inNamespace(namespace)
            .withLabel(MANAGED_BY_LABEL, MANAGED_BY)
            .inform(
                new ResourceEventHandler<>() {
                  @Override
                  public void onAdd(Pod pod) {
                    report(listener, pod, statusOf(pod));
                  }

                  @Override
                  public void onUpdate(Pod oldPod, Pod pod) {
                    report(listener, pod, statusOf(pod));
                  }

                  @Override
                  public void onDelete(Pod pod, boolean finalStateUnknown) {
                    report(listener, pod, WorkspaceStatus.FAILED);
                  }
                });
  }

  @Override
  public synchronized void close() {
    if (informer != null) {
      informer.close();
    }
  }

  private static void report(
      BiConsumer<UUID, WorkspaceStatus> listener, Pod pod, WorkspaceStatus status) {
    var id = pod.getMetadata().getLabels().get(WORKSPACE_ID_LABEL);
    if (id != null) {
      listener.accept(UUID.fromString(id), status);
    }
  }

  static WorkspaceStatus statusOf(Pod pod) {
    var status = pod.getStatus();
    var phase = status == null ? null : status.getPhase();
    if ("Failed".equals(phase) || "Succeeded".equals(phase)) {
      return WorkspaceStatus.FAILED;
    }
    var conditions = status == null ? null : status.getConditions();
    var ready =
        "Running".equals(phase)
            && conditions != null
            && conditions.stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    return ready ? WorkspaceStatus.READY : WorkspaceStatus.INITIALIZING;
  }

  private void removeEnvironment(UUID workspaceId) {
    client.secrets().inNamespace(namespace).withName(podName(workspaceId)).delete();
    client.configMaps().inNamespace(namespace).withName(podName(workspaceId)).delete();
  }

  private static Map<String, String> encode(Map<String, String> values) {
    return values.entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                e ->
                    Base64.getEncoder()
                        .encodeToString(e.getValue().getBytes(StandardCharsets.UTF_8))));
  }

  private static ObjectMeta metadata(UUID workspaceId) {
    return new ObjectMetaBuilder()
        .withName(podName(workspaceId))
        .withLabels(
            Map.of(
                "app.kubernetes.io/name",
                "workspaced-ai-workspace",
                "app.kubernetes.io/part-of",
                "workspaced-ai",
                MANAGED_BY_LABEL,
                MANAGED_BY,
                WORKSPACE_ID_LABEL,
                workspaceId.toString()))
        .build();
  }

  private Pod pod(UUID workspaceId) {
    return new PodBuilder()
        .withMetadata(metadata(workspaceId))
        .withNewSpec()
        .withAutomountServiceAccountToken(false)
        .withTerminationGracePeriodSeconds(5L)
        .withNewSecurityContext()
        .withRunAsNonRoot(true)
        .withRunAsUser(HARNESS_USER)
        .withRunAsGroup(HARNESS_USER)
        // Makes the home volume writable by that user.
        .withFsGroup(HARNESS_USER)
        .withNewSeccompProfile()
        .withType("RuntimeDefault")
        .endSeccompProfile()
        .endSecurityContext()
        .addNewContainer()
        .withName("workspace")
        .withImage(image)
        .addNewPort()
        .withName("harness")
        .withContainerPort(HARNESS_PORT)
        .endPort()
        .addNewEnvFrom()
        .withNewConfigMapRef()
        .withName(podName(workspaceId))
        .endConfigMapRef()
        .endEnvFrom()
        .addNewEnvFrom()
        .withNewSecretRef()
        .withName(podName(workspaceId))
        .endSecretRef()
        .endEnvFrom()
        .withNewSecurityContext()
        .withAllowPrivilegeEscalation(false)
        .withReadOnlyRootFilesystem(true)
        .withNewCapabilities()
        .withDrop("ALL")
        .endCapabilities()
        .endSecurityContext()
        // A TCP check: every HTTP path, health included, sits behind the password.
        .withNewReadinessProbe()
        .withNewTcpSocket()
        .withPort(new IntOrString(HARNESS_PORT))
        .endTcpSocket()
        .withPeriodSeconds(5)
        .endReadinessProbe()
        // The root filesystem is read-only, so the home and /tmp are the only places the
        // agent can write: each session's files live in the home, for the pod's lifetime.
        .addNewVolumeMount()
        .withName("home")
        .withMountPath("/home/workspace")
        .endVolumeMount()
        .addNewVolumeMount()
        .withName("tmp")
        .withMountPath("/tmp")
        .endVolumeMount()
        .withNewResources()
        .addToRequests("cpu", new Quantity("50m"))
        .addToRequests("memory", new Quantity("256Mi"))
        .addToRequests("ephemeral-storage", new Quantity("256Mi"))
        .addToLimits("cpu", new Quantity("1"))
        .addToLimits("memory", new Quantity("1Gi"))
        .addToLimits("ephemeral-storage", new Quantity("4Gi"))
        .endResources()
        .endContainer()
        .addNewVolume()
        .withName("home")
        .withNewEmptyDir()
        .endEmptyDir()
        .endVolume()
        .addNewVolume()
        .withName("tmp")
        .withNewEmptyDir()
        .endEmptyDir()
        .endVolume()
        .endSpec()
        .build();
  }
}
