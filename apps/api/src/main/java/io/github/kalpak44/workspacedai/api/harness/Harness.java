package io.github.kalpak44.workspacedai.api.harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The agent harness running in a workspace's pod. Every call names the directory the session works
 * in, because the harness scopes everything, its event stream included, to a directory.
 */
public interface Harness {

  /** The harness reads its password from this variable; no user secret may take it. */
  String PASSWORD_VARIABLE = "OPENCODE_SERVER_PASSWORD";

  /** The harness's home; requests about the whole harness rather than a session use it. */
  String HOME = "/home/workspace";

  /** Where each session's directory lives inside the pod. */
  String SESSIONS_ROOT = HOME + "/sessions";

  /**
   * Creates a session working in {@code directory}, which the harness creates.
   *
   * @return the harness's own id for it
   */
  String createSession(HarnessEndpoint endpoint, String directory, String title);

  /** Deletes the session and its directory; one already gone is not an error. */
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

  /** A model as the harness names it: the provider it comes from and its id there. */
  record ModelRef(String providerId, String modelId) {}

  /** The provider's models that can call tools, which an agent cannot work without. */
  ModelCatalogue models(HarnessEndpoint endpoint, String providerId);

  /** Each MCP server the harness knows, by name, with its connection status. */
  Map<String, String> mcpStatus(HarnessEndpoint endpoint);

  /**
   * Makes the harness's MCP servers exactly {@code servers}: every session picks the change up at
   * once. One the harness has but {@code servers} lacks is disabled, since its configuration can be
   * merged into but not deleted from.
   *
   * @return whether anything had to change
   */
  boolean configureMcp(HarnessEndpoint endpoint, Map<String, McpServerConfig> servers);

  /** A remote MCP server; a header value may be {@code {env:NAME}}, filled from the pod. */
  record McpServerConfig(String url, Map<String, String> headers) {}

  /** Stops the reply in progress, if there is one. */
  void abort(HarnessEndpoint endpoint, String directory, String sessionId);

  /**
   * Follows one session's events until the returned handle is closed or the harness ends the
   * stream, in which case {@code onEnd} runs.
   */
  AutoCloseable stream(
      HarnessEndpoint endpoint,
      String directory,
      String sessionId,
      Consumer<HarnessEvent> onEvent,
      Runnable onEnd);
}
