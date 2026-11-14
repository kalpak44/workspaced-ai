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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Every API instance schedules the expiry pass; only the one holding the lock runs it. */
@SpringBootTest
@Import({TestDatabase.class, TestRuntime.class})
class SessionExpiryLockTest {

  @MockitoBean SessionExpiryService expiry;
  @Autowired SessionExpiryScheduler cleanup;
  @Autowired WorkspaceRepository workspaces;
  @Autowired SettingsService settings;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void oneWorkspaceWithExpiryOn() {
    workspaces.deleteAll();
    // Expired rather than deleted: ShedLock remembers a lock's row exists and only updates it.
    jdbc.update("update shedlock set lock_until = timezone('utc', now()) - interval '1 second'");
    var workspace =
        workspaces.save(new WorkspaceEntity(TestUsers.ADMIN, "Research", "openrouter")).getId();
    settings.setSessionTtl(workspace, new SessionTtlSettings(true, 1));
    when(expiry.candidates(any())).thenReturn(List.of());
  }

  @Test
  void skipsThePassWhileAnotherInstanceHoldsTheLock() {
    jdbc.update(
        "insert into shedlock (name, lock_until, locked_at, locked_by)"
            + " values ('session-expiry', timezone('utc', now()) + interval '1 hour',"
            + " timezone('utc', now()), 'another-instance')"
            + " on conflict (name) do update set lock_until = excluded.lock_until,"
            + " locked_by = excluded.locked_by");
    cleanup.cleanup();
    verify(expiry, never()).candidates(any());
  }

  @Test
  void runsThePassOnceTheLockIsFree() {
    cleanup.cleanup();
    verify(expiry).candidates(any());
  }
}
