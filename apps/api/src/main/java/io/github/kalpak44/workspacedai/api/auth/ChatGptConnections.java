package io.github.kalpak44.workspacedai.api.auth;

import io.github.kalpak44.workspacedai.api.ClusterLocks;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ChatGptConnections {
  public record Attempt(
      UUID id, String url, String instructions, Instant expiresAt, int pollSeconds) {}

  public record Status(String status, UUID connectorId) {}

  private final ChatGptLoginRepository logins;
  private final AiConnectorRepository connectors;
  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final ChatGptDeviceAuth auth;
  private final ClusterLocks locks;
  private final SecretBox box;
  private final JsonMapper json;
  private final org.springframework.transaction.support.TransactionTemplate tx;

  public ChatGptConnections(
      ChatGptLoginRepository logins,
      AiConnectorRepository connectors,
      WorkspaceRepository workspaces,
      WorkspaceRuntime runtime,
      ChatGptDeviceAuth auth,
      ClusterLocks locks,
      AuthProperties properties,
      JsonMapper json,
      org.springframework.transaction.support.TransactionTemplate tx) {
    this.logins = logins;
    this.connectors = connectors;
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.auth = auth;
    this.locks = locks;
    this.json = json;
    this.tx = tx;
    box = new SecretBox(properties.jwtSecret(), "workspaced-ai-connectors");
  }

  private void owned(UUID workspace) {
    var row =
        workspaces
            .findById(workspace)
            .filter(w -> w.getOwnerId().equals(CurrentUser.id()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace"));
    if (row.isDraft() || runtime.harness(workspace).isEmpty())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Start the workspace before connecting ChatGPT");
  }

  private ChatGptLogin login(UUID workspace, UUID id) {
    owned(workspace);
    return logins
        .findById(id)
        .filter(a -> a.workspaceId.equals(workspace) && a.ownerId.equals(CurrentUser.id()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no sign-in attempt"));
  }

  public Attempt start(UUID workspace, String name) {
    owned(workspace);
    return locks.call(
        "chatgpt-start-" + workspace,
        () -> {
          // A repeated click must not create unbounded provider authorizations.
          if (logins.existsByWorkspaceIdAndEncryptedStateIsNotNullAndExpiresAtAfter(
              workspace, Instant.now()))
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "A ChatGPT sign-in is already pending for this workspace");
          var device = auth.start();
          var row = new ChatGptLogin();
          row.id = UUID.randomUUID();
          row.ownerId = CurrentUser.id();
          row.workspaceId = workspace;
          row.name = name;
          var code = ChatGptDeviceAuth.required(device, "user_code");
          row.encryptedState =
              box.seal(
                  json.writeValueAsString(
                      Map.of(
                          "device_auth_id",
                          ChatGptDeviceAuth.required(device, "device_auth_id"),
                          "user_code",
                          code)));
          row.pollSeconds = Math.max(5, Math.min(60, device.path("interval").asInt(5))) + 3;
          row.expiresAt = Instant.now().plusSeconds(600);
          row.nextPollAt = Instant.now().plusSeconds(row.pollSeconds);
          logins.saveAndFlush(row);
          return new Attempt(
              row.id,
              "https://auth.openai.com/codex/device",
              "Enter code: " + code,
              row.expiresAt,
              row.pollSeconds);
        });
  }

  public Status poll(UUID workspace, UUID id) {
    return locks.call(
        "chatgpt-login-" + id,
        () -> {
          var row = login(workspace, id);
          if (row.connectorId != null) return new Status("complete", row.connectorId);
          if (row.encryptedState == null) return new Status("cancelled", null);
          if (!row.expiresAt.isAfter(Instant.now())) {
            row.encryptedState = null;
            logins.saveAndFlush(row);
            return new Status("expired", null);
          }
          if (row.nextPollAt.isAfter(Instant.now())) return new Status("pending", null);
          row.nextPollAt = Instant.now().plusSeconds(row.pollSeconds);
          logins.saveAndFlush(row);
          var credential = auth.poll(box.open(row.encryptedState).orElseThrow());
          if (credential == null) return new Status("pending", null);
          tx.executeWithoutResult(
              _ -> {
                var saved =
                    connectors.saveAndFlush(
                        AiConnectorEntity.chatGpt(
                            row.ownerId, workspace, row.name, box.seal(credential)));
                row.connectorId = saved.getId();
                row.encryptedState = null;
                logins.saveAndFlush(row);
              });
          return new Status("complete", row.connectorId);
        });
  }

  public void cancel(UUID workspace, UUID id) {
    locks.run(
        "chatgpt-login-" + id,
        () -> {
          var row = login(workspace, id);
          row.encryptedState = null;
          logins.saveAndFlush(row);
        });
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
  @org.springframework.transaction.annotation.Transactional
  public void cleanup() {
    logins.deleteByExpiresAtBefore(Instant.now().minusSeconds(3600));
  }
}
