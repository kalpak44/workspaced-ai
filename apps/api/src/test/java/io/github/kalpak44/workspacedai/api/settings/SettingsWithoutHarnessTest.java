package io.github.kalpak44.workspacedai.api.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatusChanged;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;

/** No harness running, as while a workspace starts. */
@SpringBootTest
@Import(TestDatabase.class)
class SettingsWithoutHarnessTest {

  @Autowired SettingsService service;
  @Autowired WorkspaceRepository workspaces;
  @Autowired ApplicationEventPublisher events;

  UUID workspace;

  @BeforeEach
  void setUp() {
    workspaces.deleteAll();
    workspace = workspaces.save(new WorkspaceEntity("Research", "openrouter")).getId();
  }

  @Test
  void keepsAServerForWhenTheHarnessIsUpAndSaysItsStatusIsUnknown() {
    var added =
        service.addMcpServer(
            workspace, new McpServerRequest("github", "https://mcp.test/g", Map.of()));
    assertThat(added.status()).isEqualTo("unknown");
    // Ready with no harness to reach is tolerated, and retried on the next report.
    events.publishEvent(new WorkspaceStatusChanged(workspace, WorkspaceStatus.READY));
    service.removeMcpServer(workspace, "github");
    assertThat(service.mcpServers(workspace)).isEmpty();
  }

  @Test
  void needsTheHarnessForTheModelCatalogue() {
    assertThatThrownBy(() -> service.models(workspace))
        .isInstanceOf(HarnessUnavailableException.class);
    assertThat(service.setModel(workspace, null)).isNull();
  }
}
