package io.github.kalpak44.workspacedai.api.runtime;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Deployment of one pod per workspace, in the namespace the API itself runs in, behind a
 * Service the API calls it through. A Secret and a ConfigMap of the same name hold its environment.
 * The Deployment replaces a pod that dies, so a workspace outlives a node restart or an eviction.
 */
public class KubernetesWorkspaceRuntime implements WorkspaceRuntime, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(KubernetesWorkspaceRuntime.class);

  static final String WORKSPACE_ID_LABEL = "workspaced-ai/workspace-id";
  static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
  static final String MANAGED_BY = "workspaced-ai-api";
  static final String POD_PREFIX = "workspaced-ai-workspace-";
  static final int HARNESS_PORT = 4096;
  // Where the harness image's nginx listens, unprivileged; its Service offers it on 80.
  static final int WEB_PORT = 8080;
  // Each public name's Service is web-<name>, which is what the gateway in front resolves.
  static final String WEB_SERVICE_PREFIX = "web-";
  static final String WEB_SERVER_LABEL = "workspaced-ai/web-server";
  // The user the harness image runs as, which owns the home it works in.
  private static final long HARNESS_USER = 1000L;
  private static final String BY_WORKSPACE = "workspace";
  private static final String CONTAINER = "workspace";
  private static final long SYNC_TIMEOUT_SECONDS = 30;

  private final KubernetesClient client;
  private final String namespace;
  private final String image;
  private final long stopTimeoutMillis;
  private final String storageSize;
  private final String storageClass;
  // Started on first use and kept for the runtime's life: every status is read from them, so
  // working one out costs no call to the API server.
  private SharedIndexInformer<Pod> pods;
  private SharedIndexInformer<Deployment> deployments;
  private final AtomicReference<BiConsumer<UUID, WorkspaceStatus>> listener =
      new AtomicReference<>();

  public KubernetesWorkspaceRuntime(KubernetesClient client, WorkspaceProperties properties) {
    this.client = client;
    this.namespace = client.getNamespace();
    this.image = properties.image();
    this.stopTimeoutMillis = properties.stopTimeout().toMillis();
    this.storageSize = properties.storageSize();
    this.storageClass = properties.storageClass();
    if (namespace == null) {
      throw new IllegalStateException("cannot tell which namespace the API runs in");
    }
  }

  private static Container harnessContainer(Deployment deployment) {
    return deployment.getSpec().getTemplate().getSpec().getContainers().stream()
        .filter(c -> CONTAINER.equals(c.getName()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("the Deployment has no harness container"));
  }

  /**
   * The Deployment, Service, Secret and ConfigMap all share this name. At 60 characters it fits the
   * 63 a Service name may have.
   */
  static String resourceName(UUID workspaceId) {
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
      client
          .persistentVolumeClaims()
          .inNamespace(namespace)
          .resource(
              new PersistentVolumeClaimBuilder()
                  .withMetadata(metadata(workspaceId))
                  .withNewSpec()
                  .withAccessModes("ReadWriteOnce")
                  .withStorageClassName(storageClass.isBlank() ? null : storageClass)
                  .withNewResources()
                  .addToRequests("storage", new Quantity(storageSize))
                  .endResources()
                  .endSpec()
                  .build())
          .create();
      client.apps().deployments().inNamespace(namespace).resource(deployment(workspaceId)).create();
      client.services().inNamespace(namespace).resource(service(workspaceId)).create();
    } catch (RuntimeException e) {
      removeAll(workspaceId);
      throw e;
    }
  }

  /**
   * Waits until no pod of the workspace is left, so a delete reports the workspace gone only once
   * it is. The bare pod a workspace ran as before Deployments is removed by name.
   */
  @Override
  public void stop(UUID workspaceId) {
    removeAll(workspaceId);
    try {
      client
          .pods()
          .inNamespace(namespace)
          .withLabel(WORKSPACE_ID_LABEL, workspaceId.toString())
          .informOnCondition(List::isEmpty)
          .get(stopTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      throw new IllegalStateException("workspace " + workspaceId + " did not stop in time", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted stopping workspace " + workspaceId, e);
    }
  }

  /**
   * Deletes the pods and lets the Deployment bring up the next, which mounts the same home and
   * reads the Secret afresh. A workspace still on an older harness image is moved to the current
   * one instead, and the Deployment replaces its pod by itself; a restart is how a workspace made
   * before a harness release picks it up. The bare pod a workspace ran as before Deployments has
   * nothing to replace it, so it is left alone.
   */
  @Override
  public void restart(UUID workspaceId) {
    var deployment =
        client.apps().deployments().inNamespace(namespace).withName(resourceName(workspaceId));
    var current = deployment.get();
    if (current == null) {
      throw new IllegalStateException("workspace " + workspaceId + " has no Deployment");
    }
    if (!image.equals(harnessContainer(current).getImage())) {
      deployment.edit(
          d -> {
            harnessContainer(d).setImage(image);
            return d;
          });
      return;
    }
    client
        .pods()
        .inNamespace(namespace)
        .withLabel(WORKSPACE_ID_LABEL, workspaceId.toString())
        .delete();
  }

  /**
   * A Service of its own, named for the public name, so whatever routes the domain inside the
   * cluster finds the workspace by that name alone. Publishing under another name replaces it.
   */
  @Override
  public void publish(UUID workspaceId, String name) {
    var service = webServiceName(name);
    var existing = client.services().inNamespace(namespace).withName(service).get();
    if (existing != null && !workspaceOf(existing).contains(workspaceId.toString())) {
      throw new IllegalStateException(service + " belongs to another workspace");
    }
    if (existing == null) {
      client.services().inNamespace(namespace).resource(webService(workspaceId, name)).create();
    }
    webServices(workspaceId).stream()
        .filter(s -> !s.getMetadata().getName().equals(service))
        .forEach(s -> client.services().inNamespace(namespace).resource(s).delete());
  }

  @Override
  public void unpublish(UUID workspaceId) {
    webServices(workspaceId)
        .forEach(s -> client.services().inNamespace(namespace).resource(s).delete());
  }

  static String webServiceName(String name) {
    return WEB_SERVICE_PREFIX + name;
  }

  /** Listed and deleted one by one: the API server offers no deletecollection on Services. */
  private List<Service> webServices(UUID workspaceId) {
    return client
        .services()
        .inNamespace(namespace)
        .withLabel(WORKSPACE_ID_LABEL, workspaceId.toString())
        .withLabel(WEB_SERVER_LABEL)
        .list()
        .getItems();
  }

  private static Service webService(UUID workspaceId, String name) {
    return new ServiceBuilder()
        .withMetadata(
            new ObjectMetaBuilder(metadata(workspaceId))
                .withName(webServiceName(name))
                .addToLabels(WEB_SERVER_LABEL, name)
                .build())
        .withNewSpec()
        .addToSelector(WORKSPACE_ID_LABEL, workspaceId.toString())
        .addToSelector(MANAGED_BY_LABEL, MANAGED_BY)
        .addNewPort()
        .withName("http")
        .withPort(80)
        .withTargetPort(new IntOrString(WEB_PORT))
        .endPort()
        .endSpec()
        .build();
  }

  /** Creates the Secret when it is missing, as it is for a workspace made before secrets were. */
  @Override
  public void putSecret(UUID workspaceId, String name, String value) {
    var secret = client.secrets().inNamespace(namespace).withName(resourceName(workspaceId));
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
  public Optional<String> secret(UUID workspaceId, String name) {
    var secret = client.secrets().inNamespace(namespace).withName(resourceName(workspaceId)).get();
    var data = secret == null ? null : secret.getData();
    return Optional.ofNullable(data == null ? null : data.get(name))
        .map(value -> new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8));
  }

  @Override
  public void removeSecret(UUID workspaceId, String name) {
    var secret = client.secrets().inNamespace(namespace).withName(resourceName(workspaceId));
    var current = secret.get();
    if (current != null && current.getData() != null && current.getData().containsKey(name)) {
      secret.edit(s -> new SecretBuilder(s).removeFromData(name).build());
    }
  }

  /**
   * The newest pod's, since an older one is on its way out. A pod whose container has not started
   * has no log yet, and a missing permission is not a reason to fail the caller: both read as
   * nothing printed.
   */
  @Override
  public List<String> recentLog(UUID workspaceId, int lines) {
    try {
      var newest =
          client
              .pods()
              .inNamespace(namespace)
              .withLabel(WORKSPACE_ID_LABEL, workspaceId.toString())
              .list()
              .getItems()
              .stream()
              .filter(p -> p.getMetadata().getDeletionTimestamp() == null)
              .max(Comparator.comparing(p -> p.getMetadata().getCreationTimestamp()));
      if (newest.isEmpty()) {
        return List.of();
      }
      var log =
          client
              .pods()
              .inNamespace(namespace)
              .withName(newest.get().getMetadata().getName())
              .inContainer(CONTAINER)
              .tailingLines(lines)
              .getLog();
      return log == null || log.isEmpty() ? List.of() : List.of(log.split("\n"));
    } catch (KubernetesClientException e) {
      LOG.debug("could not read the log of workspace {}", workspaceId, e);
      return List.of();
    }
  }

  /**
   * Through the workspace's Service, by its fully qualified name so the lookup does not depend on
   * the API pod's DNS search path. Empty for a workspace with no Service or no password, which is
   * one made before the harness existed.
   */
  @Override
  public Optional<HarnessEndpoint> harness(UUID workspaceId) {
    var name = resourceName(workspaceId);
    var secret = client.secrets().inNamespace(namespace).withName(name).get();
    var data = secret == null ? null : secret.getData();
    var passwordKey =
        data != null && data.containsKey(Harness.PASSWORD_VARIABLE)
            ? Harness.PASSWORD_VARIABLE
            : "OPENCODE_SERVER_PASSWORD";
    if (data == null
        || !data.containsKey(passwordKey)
        || client.services().inNamespace(namespace).withName(name).get() == null) {
      return Optional.empty();
    }
    var password =
        new String(Base64.getDecoder().decode(data.get(passwordKey)), StandardCharsets.UTF_8);
    var url = "http://" + name + "." + namespace + ".svc:" + HARNESS_PORT;
    return Optional.of(new HarnessEndpoint(URI.create(url), password));
  }

  /**
   * Read from the informers' caches, which the API server keeps current, rather than by listing
   * every workspace's pods and Deployments on each call. A workspace with no Deployment in the
   * cache is looked up by name, in case it was made a moment ago and the cache has not caught up;
   * that is one small read, and only for a workspace being created or one already gone.
   */
  @Override
  public Map<UUID, WorkspaceStatus> statuses(Collection<UUID> workspaceIds) {
    informers();
    var statuses = new HashMap<UUID, WorkspaceStatus>();
    for (var id : workspaceIds) {
      var own = pods.getIndexer().byIndex(BY_WORKSPACE, id.toString());
      WorkspaceStatus status;
      if (own.stream().anyMatch(p -> statusOf(p) == WorkspaceStatus.READY)) {
        status = WorkspaceStatus.READY;
      } else if (hasDeployment(id)) {
        status = WorkspaceStatus.INITIALIZING;
      } else if (!own.isEmpty()) {
        status = statusOf(own.getFirst());
      } else {
        status = WorkspaceStatus.FAILED;
      }
      statuses.put(id, status);
    }
    return statuses;
  }

  private boolean hasDeployment(UUID id) {
    return !deployments.getIndexer().byIndex(BY_WORKSPACE, id.toString()).isEmpty()
        || client.apps().deployments().inNamespace(namespace).withName(resourceName(id)).get()
            != null;
  }

  /**
   * The API server pushes each pod change as it happens. The workspace's status is worked out again
   * on each, because one pod going is not the workspace going while its Deployment brings up the
   * next. A later call replaces the listener.
   */
  @Override
  public void watch(BiConsumer<UUID, WorkspaceStatus> listener) {
    this.listener.set(listener);
    informers();
  }

  @Override
  public synchronized void close() {
    if (pods != null) {
      pods.close();
    }
    if (deployments != null) {
      deployments.close();
    }
  }

  /** Both caches, filled before this returns, so a status read straight after is complete. */
  private synchronized void informers() {
    if (pods != null) {
      return;
    }
    var podInformer =
        client
            .pods()
            .inNamespace(namespace)
            .withLabel(MANAGED_BY_LABEL, MANAGED_BY)
            .runnableInformer(0);
    podInformer.addIndexers(Map.of(BY_WORKSPACE, KubernetesWorkspaceRuntime::workspaceOf));
    podInformer.addEventHandler(
        new ResourceEventHandler<>() {
          @Override
          public void onAdd(Pod pod) {
            report(pod);
          }

          @Override
          public void onUpdate(Pod oldPod, Pod pod) {
            report(pod);
          }

          @Override
          public void onDelete(Pod pod, boolean finalStateUnknown) {
            report(pod);
          }
        });
    var deploymentInformer =
        client
            .apps()
            .deployments()
            .inNamespace(namespace)
            .withLabel(MANAGED_BY_LABEL, MANAGED_BY)
            .runnableInformer(0);
    deploymentInformer.addIndexers(Map.of(BY_WORKSPACE, KubernetesWorkspaceRuntime::workspaceOf));
    try {
      deploymentInformer.start().toCompletableFuture().get(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      podInformer.start().toCompletableFuture().get(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted watching workspaces", e);
    } catch (ExecutionException | TimeoutException e) {
      podInformer.close();
      deploymentInformer.close();
      throw new IllegalStateException("could not watch workspaces", e);
    }
    deployments = deploymentInformer;
    pods = podInformer;
  }

  private static List<String> workspaceOf(HasMetadata resource) {
    var labels = resource.getMetadata().getLabels();
    var id = labels == null ? null : labels.get(WORKSPACE_ID_LABEL);
    return id == null ? List.of() : List.of(id);
  }

  /**
   * A lookup that fails, as one does while the client shuts down, is skipped rather than thrown
   * into the informer: the next change or the next client's snapshot reports the status again.
   */
  private void report(Pod pod) {
    var listener = this.listener.get();
    var label = workspaceOf(pod);
    if (listener == null || label.isEmpty()) {
      return;
    }
    var id = UUID.fromString(label.getFirst());
    try {
      listener.accept(id, statuses(List.of(id)).get(id));
    } catch (KubernetesClientException e) {
      LOG.debug("could not work out the status of workspace {}", id, e);
    }
  }

  /**
   * A pod on its way out, as one is through a restart, is starting rather than ready: it keeps its
   * Ready condition until its container stops, which would hide the restart from the status.
   */
  static WorkspaceStatus statusOf(Pod pod) {
    if (pod.getMetadata() != null && pod.getMetadata().getDeletionTimestamp() != null) {
      return WorkspaceStatus.INITIALIZING;
    }
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

  /** Each delete is a no-op for what is not there, so this also cleans up after a partial start. */
  private void removeAll(UUID workspaceId) {
    var name = resourceName(workspaceId);
    client
        .apps()
        .deployments()
        .inNamespace(namespace)
        .withName(name)
        .withPropagationPolicy(DeletionPropagation.BACKGROUND)
        .delete();
    unpublish(workspaceId);
    client.services().inNamespace(namespace).withName(name).delete();
    client.pods().inNamespace(namespace).withName(name).delete();
    client.secrets().inNamespace(namespace).withName(name).delete();
    client.configMaps().inNamespace(namespace).withName(name).delete();
    client.persistentVolumeClaims().inNamespace(namespace).withName(name).delete();
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
        .withName(resourceName(workspaceId))
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

  /**
   * Recreate, not a rolling update: a workspace is one home, and two pods would be two homes with
   * the Service splitting requests between them.
   */
  private Deployment deployment(UUID workspaceId) {
    return new DeploymentBuilder()
        .withMetadata(metadata(workspaceId))
        .withNewSpec()
        .withReplicas(1)
        .withRevisionHistoryLimit(1)
        .withNewStrategy()
        .withType("Recreate")
        .endStrategy()
        .withNewSelector()
        .addToMatchLabels(WORKSPACE_ID_LABEL, workspaceId.toString())
        .addToMatchLabels(MANAGED_BY_LABEL, MANAGED_BY)
        .endSelector()
        .withNewTemplate()
        .withNewMetadata()
        .withLabels(metadata(workspaceId).getLabels())
        .endMetadata()
        .withSpec(podSpec(workspaceId))
        .endTemplate()
        .endSpec()
        .build();
  }

  private static Service service(UUID workspaceId) {
    return new ServiceBuilder()
        .withMetadata(metadata(workspaceId))
        .withNewSpec()
        .addToSelector(WORKSPACE_ID_LABEL, workspaceId.toString())
        .addToSelector(MANAGED_BY_LABEL, MANAGED_BY)
        .addNewPort()
        .withName("harness")
        .withPort(HARNESS_PORT)
        .withTargetPort(new IntOrString("harness"))
        .endPort()
        .endSpec()
        .build();
  }

  private PodSpec podSpec(UUID workspaceId) {
    return new PodSpecBuilder()
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
        .withName(CONTAINER)
        .withImage(image)
        .addNewPort()
        .withName("harness")
        .withContainerPort(HARNESS_PORT)
        .endPort()
        .addNewEnvFrom()
        .withNewConfigMapRef()
        .withName(resourceName(workspaceId))
        .endConfigMapRef()
        .endEnvFrom()
        .addNewEnvFrom()
        .withNewSecretRef()
        .withName(resourceName(workspaceId))
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
        // The root filesystem is read-only; the persistent home keeps sessions and project
        // files across replacement pods, while /tmp remains disposable.
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
        .withNewPersistentVolumeClaim()
        .withClaimName(resourceName(workspaceId))
        .endPersistentVolumeClaim()
        .endVolume()
        .addNewVolume()
        .withName("tmp")
        .withNewEmptyDir()
        .endEmptyDir()
        .endVolume()
        .build();
  }
}
