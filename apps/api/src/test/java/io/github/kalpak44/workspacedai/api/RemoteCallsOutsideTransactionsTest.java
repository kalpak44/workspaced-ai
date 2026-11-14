package io.github.kalpak44.workspacedai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.harness.ChatMessage;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.ModelCatalogue;
import io.github.kalpak44.workspacedai.api.harness.ModelOption;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.session.SessionService;
import io.github.kalpak44.workspacedai.api.settings.SettingsService;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A harness, a provider or a pod can each take tens of seconds to answer. None of them may be
 * called while a transaction holds a database connection, or a handful of slow workspaces would
 * exhaust the pool for every user.
 */
@SpringBootTest
@Import({TestDatabase.class, TestRuntime.class})
class RemoteCallsOutsideTransactionsTest {

  private static final HarnessEndpoint ENDPOINT =
      new HarnessEndpoint(URI.create("http://harness:4096"), "secret");

  @MockitoBean Harness harness;
  @MockitoBean WorkspaceRuntime runtime;
  @MockitoBean ApiKeyCheck keyCheck;

  @Autowired WorkspaceService workspaces;
  @Autowired SessionService sessions;
  @Autowired SettingsService settings;
  @Autowired WorkspaceRepository repository;

  /** Every remote call made, and whether a transaction was open at the time. */
  private final Map<String, Boolean> calls = new ConcurrentHashMap<>();

  private <T> Answer<T> remote(String name, T result) {
    return _ -> {
      calls.merge(
          name, TransactionSynchronizationManager.isActualTransactionActive(), Boolean::logicalOr);
      return result;
    };
  }

  @BeforeEach
  void stubRemotes() {
    repository.deleteAll();
    when(runtime.harness(any())).thenReturn(Optional.of(ENDPOINT));
    when(runtime.statuses(any()))
        .thenAnswer(
            i ->
                ((java.util.Collection<?>) i.getArgument(0))
                    .stream()
                        .collect(
                            java.util.stream.Collectors.toMap(
                                id -> (java.util.UUID) id, _ -> WorkspaceStatus.READY)));
    doAnswer(remote("keyCheck", true)).when(keyCheck).accepts(any(), any());
    doAnswer(remote("stop", null)).when(runtime).stop(any());
    doAnswer(remote("createDirectory", null)).when(harness).createDirectory(any(), any());
    doAnswer(remote("validate", null)).when(harness).validate(any(), any());
    doAnswer(remote("createSession", "ses_1")).when(harness).createSession(any(), any(), any());
    doAnswer(remote("sessions", List.of())).when(harness).sessions(any());
    doAnswer(remote("messages", List.of(new ChatMessage("msg_1", "user", List.of()))))
        .when(harness)
        .messages(any(), any(), any());
    doAnswer(remote("prompt", null)).when(harness).prompt(any(), any(), any(), any(), any());
    doAnswer(remote("editMessage", null))
        .when(harness)
        .editMessage(any(), any(), any(), any(), any(), any());
    doAnswer(remote("abort", null)).when(harness).abort(any(), any(), any());
    doAnswer(remote("deleteSession", null)).when(harness).deleteSession(any(), any(), any());
    doAnswer(remote("removeDirectory", null)).when(harness).removeDirectory(any(), any());
    doAnswer(
            remote(
                "models",
                new ModelCatalogue("m", List.of(new ModelOption("m", "M", null, null, null)))))
        .when(harness)
        .models(any(), any());
  }

  @Test
  void holdsNoTransactionWhileAHarnessProviderOrPodIsWorking() {
    var workspace =
        workspaces.create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1", null);
    var session = sessions.create(workspace.id());
    sessions.list(workspace.id());
    sessions.messages(workspace.id(), session.id());
    sessions.send(workspace.id(), session.id(), "hello");
    sessions.edit(workspace.id(), session.id(), "msg_1", "hello again");
    sessions.abort(workspace.id(), session.id());
    settings.setModel(workspace.id(), "m");
    sessions.delete(workspace.id(), session.id());
    workspaces.delete(workspace.id());

    assertThat(calls.keySet())
        .containsAll(
            Set.of(
                "keyCheck",
                "createDirectory",
                "createSession",
                "sessions",
                "messages",
                "prompt",
                "editMessage",
                "abort",
                "models",
                "deleteSession",
                "removeDirectory",
                "stop"));
    assertThat(calls).allSatisfy((name, inTransaction) -> assertThat(inTransaction).isFalse());
    assertThat(repository.findAll()).isEmpty();
  }
}
