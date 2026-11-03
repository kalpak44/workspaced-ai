package io.github.kalpak44.workspacedai.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** No harness configured, as for a workspace whose pod is gone. */
@SpringBootTest
@Import(TestDatabase.class)
class SessionWithoutHarnessTest {

  @Autowired SessionService service;
  @Autowired WorkspaceRepository workspaces;
  @Autowired WorkspaceSessionRepository sessions;

  UUID workspace;

  @BeforeEach
  void setUp() {
    workspaces.deleteAll();
    workspace = workspaces.save(new WorkspaceEntity("Research", "openrouter")).getId();
  }

  @Test
  void cannotStartOrChatInASessionWithNoHarness() {
    assertThatThrownBy(() -> service.create(workspace))
        .isInstanceOf(HarnessUnavailableException.class);
    var stored =
        sessions.save(new WorkspaceSessionEntity(UUID.randomUUID(), workspace, "/p", "ses_1"));
    assertThatThrownBy(() -> service.send(workspace, stored.getId(), "hi"))
        .isInstanceOf(HarnessUnavailableException.class);
    assertThatThrownBy(() -> service.target(workspace, stored.getId()))
        .isInstanceOf(HarnessUnavailableException.class);
  }

  @Test
  void stillDeletesASessionWhoseHarnessIsGone() {
    var stored =
        sessions.save(new WorkspaceSessionEntity(UUID.randomUUID(), workspace, "/p", "ses_1"));
    service.delete(workspace, stored.getId());
    assertThat(sessions.findAll()).isEmpty();
  }
}
