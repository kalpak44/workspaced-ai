package io.github.kalpak44.workspacedai.api.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** A name must not be listed for a value the runtime never got, or kept for one it dropped. */
@SpringBootTest
@Import(TestDatabase.class)
class SecretRollbackTest {

  @MockitoBean WorkspaceRuntime runtime;
  @Autowired SecretService service;
  @Autowired WorkspaceRepository workspaces;
  @Autowired WorkspaceSecretRepository secrets;

  UUID id;

  @BeforeEach
  void setUp() {
    workspaces.deleteAll();
    id = workspaces.save(new WorkspaceEntity("Research", "openrouter")).getId();
  }

  @Test
  void keepsNoNameWhoseValueCouldNotBeStored() {
    doThrow(new IllegalStateException("cluster down")).when(runtime).putSecret(any(), any(), any());
    assertThatThrownBy(() -> service.add(id, "TOKEN", "x")).hasMessage("cluster down");
    assertThat(secrets.findAll()).isEmpty();
  }

  @Test
  void keepsTheNameWhoseValueCouldNotBeRemoved() {
    secrets.save(new WorkspaceSecretEntity(id, "TOKEN"));
    doThrow(new IllegalStateException("cluster down")).when(runtime).removeSecret(any(), any());
    assertThatThrownBy(() -> service.remove(id, "TOKEN")).hasMessage("cluster down");
    assertThat(secrets.findAll())
        .extracting(WorkspaceSecretEntity::getName)
        .containsExactly("TOKEN");
  }
}
