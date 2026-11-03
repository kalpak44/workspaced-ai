package io.github.kalpak44.workspacedai.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.runtime.MockKubernetes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The API as it runs in the cluster: Kubernetes detected, pods on a mock API server. */
@SpringBootTest(properties = "spring.main.cloud-platform=kubernetes")
@Import({TestDatabase.class, MockKubernetes.class})
class WorkspacePodTest {

  @Autowired WorkspaceService service;
  @Autowired KubernetesClient client;
  @Autowired WorkspaceRepository repository;

  @BeforeEach
  void emptyTable() {
    repository.deleteAll();
  }

  @Test
  void createsAPodPerWorkspaceAndRemovesItWithTheWorkspace() {
    var workspace = service.create("Research");
    assertThat(workspace.status()).isEqualTo(WorkspaceStatus.INITIALIZING);
    assertThat(pods()).containsExactly("workspaced-ai-workspace-" + workspace.id());

    assertThat(service.list())
        .extracting(Workspace::status)
        .containsExactly(WorkspaceStatus.INITIALIZING);
    assertThat(service.rename(workspace.id(), "Notes"))
        .map(Workspace::status)
        .hasValue(WorkspaceStatus.INITIALIZING);

    assertThat(service.delete(workspace.id())).isTrue();
    assertThat(pods()).isEmpty();
  }

  private List<String> pods() {
    return client.pods().inNamespace(client.getNamespace()).list().getItems().stream()
        .map(p -> p.getMetadata().getName())
        .toList();
  }
}
