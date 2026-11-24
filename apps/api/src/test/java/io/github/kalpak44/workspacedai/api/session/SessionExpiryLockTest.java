package io.github.kalpak44.workspacedai.api.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.settings.SessionTtlSettings;
import io.github.kalpak44.workspacedai.api.settings.SettingsService;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Every API instance schedules the expiry pass; only the one holding the lock runs it. */
@SpringBootTest
@Import({TestDatabase.class, TestRuntime.class})
class SessionExpiryLockTest {

  @MockitoBean SessionExpiryService expiry;
  @Autowired SessionExpiryScheduler cleanup;
  @Autowired WorkspaceRepository workspaces;
  @Autowired SettingsService settings;
  @Autowired LockProvider locks;

  @BeforeEach
  void oneWorkspaceWithExpiryOn() {
    workspaces.deleteAll();
    var workspace =
        workspaces
            .save(new WorkspaceEntity(TestUsers.ADMIN, "Research", "openrouter", "UTC"))
            .getId();
    settings.setSessionTtl(workspace, new SessionTtlSettings(true, 1));
    when(expiry.candidates(any())).thenReturn(List.of());
  }

  @Test
  void skipsThePassWhileAnotherInstanceHoldsTheLock() {
    // Taken as another instance would take it, for an hour.
    var held =
        locks
            .lock(
                new LockConfiguration(
                    Instant.now(), "session-expiry", Duration.ofHours(1), Duration.ZERO))
            .orElseThrow();
    try {
      cleanup.cleanup();
    } finally {
      held.unlock();
    }
    verify(expiry, never()).candidates(any());
  }

  @Test
  void runsThePassOnceTheLockIsFree() {
    cleanup.cleanup();
    verify(expiry).candidates(any());
  }
}
