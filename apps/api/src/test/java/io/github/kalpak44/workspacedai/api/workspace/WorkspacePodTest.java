package io.github.kalpak44.workspacedai.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.runtime.MockKubernetes;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The API as it runs in the cluster: Kubernetes detected, workloads on a mock API server. */
@SpringBootTest(properties = "spring.main.cloud-platform=kubernetes")
@Import({TestDatabase.class, MockKubernetes.class})
class WorkspacePodTest {

  @Autowired WorkspaceService service;
  @Autowired KubernetesClient client;
  @Autowired WorkspaceRepository repository;
  @MockitoBean ApiKeyCheck keyCheck;

  @BeforeEach
  void emptyTable() {
    repository.deleteAll();
    when(keyCheck.accepts(any(), any())).thenReturn(true);
  }

  @Test
  void runsEachWorkspaceAsADeploymentBehindAServiceAndRemovesThem() {
    var workspace = service.create("Research", ModelProvider.OPENROUTER, "sk-or-1");
    assertThat(workspace.status()).isEqualTo(WorkspaceStatus.INITIALIZING);
    var name = "workspaced-ai-workspace-" + workspace.id();
    assertThat(deployments()).containsExactly(name);
    assertThat(services()).containsExactly(name);

    // The key and the harness's password reach the cluster as a Secret, the base URL as a
    // ConfigMap, and nothing else.
    var namespace = client.getNamespace();
    var secret = client.secrets().inNamespace(namespace).withName(name).get();
    assertThat(secret.getData()).containsOnlyKeys("OPENROUTER_API_KEY", "OPENCODE_PASSWORD");
    assertThat(secret.getData().get("OPENCODE_PASSWORD")).isNotBlank();
    assertThat(
            new String(
                Base64.getDecoder().decode(secret.getData().get("OPENROUTER_API_KEY")),
                StandardCharsets.UTF_8))
        .isEqualTo("sk-or-1");
    assertThat(client.configMaps().inNamespace(namespace).withName(name).get().getData())
        .containsExactlyEntriesOf(Map.of("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"));

    assertThat(service.list())
        .extracting(Workspace::status)
        .containsExactly(WorkspaceStatus.INITIALIZING);
    assertThat(service.rename(workspace.id(), "Notes"))
        .map(Workspace::status)
        .hasValue(WorkspaceStatus.INITIALIZING);

    assertThat(service.delete(workspace.id())).isTrue();
    assertThat(deployments()).isEmpty();
    assertThat(services()).isEmpty();
    assertThat(client.secrets().inNamespace(namespace).withName(name).get()).isNull();
    assertThat(client.configMaps().inNamespace(namespace).withName(name).get()).isNull();
  }

  private List<String> deployments() {
    return client.apps().deployments().inNamespace(client.getNamespace()).list().getItems().stream()
        .map(d -> d.getMetadata().getName())
        .toList();
  }

  private List<String> services() {
    return client.services().inNamespace(client.getNamespace()).list().getItems().stream()
        .map(d -> d.getMetadata().getName())
        .toList();
  }
}
