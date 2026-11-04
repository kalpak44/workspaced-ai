package io.github.kalpak44.workspacedai.api.harness;

import io.github.kalpak44.workspacedai.api.runtime.WorkspaceTerminal;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * OpenCode V2 running in a workspace's pod. Session locations are explicit; events span the
 * service.
 */
public interface Harness {

  /** The harness reads its password from this variable; no user secret may take it. */
  String PASSWORD_VARIABLE = "OPENCODE_PASSWORD";

  /** The harness's home; requests about the whole harness rather than a session use it. */
  String HOME = "/home/workspace";

  /** The shared project every ordinary session and workspace terminal works in. */
  String PROJECT_ROOT = HOME + "/project";

  /** Fails when the server is not OpenCode V2 or the project location cannot be resolved. */
  void validate(HarnessEndpoint endpoint, String directory);

  /** Every root session currently held by this workspace's OpenCode service. */
  List<HarnessSession> sessions(HarnessEndpoint endpoint);

  /**
   * Creates a session working in the existing {@code directory}.
   *
   * @return the harness's own id for it
   */
  String createSession(HarnessEndpoint endpoint, String directory, String title);

  /** Deletes the conversation, retaining shared project files; one already gone is not an error. */
  void deleteSession(HarnessEndpoint endpoint, String directory, String sessionId);

  List<ChatMessage> messages(HarnessEndpoint endpoint, String directory, String sessionId);

  /** The name the harness gave the session, or empty while it still has its placeholder. */
  Optional<String> title(HarnessEndpoint endpoint, String directory, String sessionId);

  /**
   * Starts a reply and returns at once; the reply arrives on {@link #stream}.
   *
   * @param model a provider and model id, or null for the harness's default
   */
  void prompt(
      HarnessEndpoint endpoint, String directory, String sessionId, String text, ModelRef model);

  /** Removes a user turn and its successors, retaining project files, then starts a new reply. */
  void editMessage(
      HarnessEndpoint endpoint,
      String directory,
      String sessionId,
      String messageId,
      String text,
      ModelRef model);

  /** A model as the harness names it: the provider it comes from and its id there. */
  record ModelRef(String providerId, String modelId) {}

  /** The provider's models that can call tools, which an agent cannot work without. */
  ModelCatalogue models(HarnessEndpoint endpoint, String providerId);

  /** Each MCP server the harness knows, by name, with its connection status. */
  Map<String, String> mcpStatus(HarnessEndpoint endpoint);

  Map<String, McpServerConfig> mcpConfiguration(HarnessEndpoint endpoint);

  /**
   * Persists MCP entries in the project configuration and reloads OpenCode.
   *
   * @return whether anything had to change
   */
  boolean configureMcp(HarnessEndpoint endpoint, Map<String, McpServerConfig> servers);

  /** A remote MCP server; a header value may be {@code {env:NAME}}, filled from the pod. */
  record McpServerConfig(String url, Map<String, String> headers) {}

  /** Stops the reply in progress, if there is one. */
  void abort(HarnessEndpoint endpoint, String directory, String sessionId);

  /** Opens a Bash PTY through OpenCode and streams its output. */
  WorkspaceTerminal terminal(
      HarnessEndpoint endpoint, String directory, OutputStream output, Runnable onExit);

  /**
   * Follows the workspace-wide V2 event stream until the returned handle is closed or the harness
   * ends it. Callers fan its envelopes out to their session subscribers.
   */
  AutoCloseable stream(
      HarnessEndpoint endpoint, Consumer<HarnessEventEnvelope> onEvent, Runnable onEnd);

  /** Removes one runtime MCP server. Missing servers are ignored. */
  void removeMcp(HarnessEndpoint endpoint, String name);
}
