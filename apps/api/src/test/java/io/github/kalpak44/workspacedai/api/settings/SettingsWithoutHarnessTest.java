package io.github.kalpak44.workspacedai.api.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
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
@Import({TestDatabase.class, TestRuntime.class})
class SettingsWithoutHarnessTest {

  @Autowired SettingsService service;
  @Autowired WorkspaceRepository workspaces;
  @Autowired ApplicationEventPublisher events;

  UUID workspace;

  @BeforeEach
  void setUp() {
    workspaces.deleteAll();
    workspace =
        workspaces.save(new WorkspaceEntity(TestUsers.ADMIN, "Research", "openrouter")).getId();
  }

  @Test
  void sessionTtlDoesNotRequireARunningHarnessAndIsWorkspaceScoped() {
    assertThat(service.sessionTtl(workspace)).isEqualTo(new SessionTtlSettings(false, 168));
    assertThat(service.setSessionTtl(workspace, new SessionTtlSettings(true, 6)))
        .isEqualTo(new SessionTtlSettings(true, 6));
    var other =
        workspaces.save(new WorkspaceEntity(TestUsers.ADMIN, "Other", "openrouter")).getId();
    assertThat(service.sessionTtl(other)).isEqualTo(new SessionTtlSettings(false, 168));
    assertThat(service.sessionTtl(workspace)).isEqualTo(new SessionTtlSettings(true, 6));
  }

  @Test
  void requiresTheWorkspaceForMcpConfiguration() {
    assertThatThrownBy(
            () ->
                service.addMcpServer(
                    workspace, new McpServerRequest("github", "https://mcp.test/g", Map.of())))
        .isInstanceOf(HarnessUnavailableException.class);
    assertThatThrownBy(() -> service.mcpServers(workspace))
        .isInstanceOf(HarnessUnavailableException.class);
  }

  @Test
  void needsTheHarnessForTheModelCatalogue() {
    assertThatThrownBy(() -> service.models(workspace))
        .isInstanceOf(HarnessUnavailableException.class);
    assertThat(service.setModel(workspace, null)).isNull();
  }
}
