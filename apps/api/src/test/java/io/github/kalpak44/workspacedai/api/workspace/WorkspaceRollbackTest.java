package io.github.kalpak44.workspacedai.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** A runtime failure must not leave a stored workspace without a VM, or the reverse. */
@SpringBootTest
@Import({TestDatabase.class, TestRuntime.class})
class WorkspaceRollbackTest {

  @MockitoBean WorkspaceRuntime runtime;
  @MockitoBean ApiKeyCheck keyCheck;
  @Autowired WorkspaceService service;
  @Autowired WorkspaceRepository repository;

  @BeforeEach
  void emptyTable() {
    repository.deleteAll();
    when(keyCheck.accepts(any(), any())).thenReturn(true);
  }

  @Test
  void keepsNoWorkspaceWhoseVmCouldNotStartAndReleasesIt() {
    doThrow(new IllegalStateException("VM manager down"))
        .when(runtime)
        .start(any(), any(), any(), any());
    assertThatThrownBy(
            () ->
                service.create(
                    TestUsers.ADMIN, "Orphan", ModelProvider.OPENROUTER, "sk-or-1", null))
        .hasMessage("VM manager down");
    assertThat(repository.findAll()).isEmpty();
    verify(runtime).stop(any());
  }

  @Test
  void startsNothingForARejectedKey() {
    when(keyCheck.accepts(ModelProvider.OPENROUTER, "sk-bad")).thenReturn(false);
    assertThatThrownBy(
            () -> service.create(TestUsers.ADMIN, "Nope", ModelProvider.OPENROUTER, "sk-bad", null))
        .isInstanceOf(ApiKeyRejectedException.class);
    assertThat(repository.findAll()).isEmpty();
    verify(runtime, never()).start(any(), any(), any(), any());
  }

  @Test
  void keepsTheWorkspaceWhoseVmCouldNotStop() {
    var kept = repository.save(new WorkspaceEntity(TestUsers.ADMIN, "Kept", "openrouter"));
    doThrow(new IllegalStateException("VM manager down")).when(runtime).stop(any());
    assertThatThrownBy(() -> service.delete(kept.getId())).hasMessage("VM manager down");
    assertThat(repository.findAll())
        .extracting(WorkspaceEntity::getId)
        .containsExactly(kept.getId());
  }

  @Test
  void reportsAnUnknownIdWithoutTouchingTheRuntime() {
    assertThat(service.delete(UUID.randomUUID())).isFalse();
  }
}
