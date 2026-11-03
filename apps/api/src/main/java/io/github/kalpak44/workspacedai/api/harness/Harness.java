package io.github.kalpak44.workspacedai.api.harness;

import java.util.List;
import java.util.function.Consumer;

/**
 * The agent harness running in a workspace's pod. Every call names the directory the session works
 * in, because the harness scopes everything, its event stream included, to a directory.
 */
public interface Harness {

  /** The harness reads its password from this variable; no user secret may take it. */
  String PASSWORD_VARIABLE = "OPENCODE_SERVER_PASSWORD";

  /** Where each session's directory lives inside the pod. */
  String SESSIONS_ROOT = "/home/workspace/sessions";

  /**
   * Creates a session working in {@code directory}, which the harness creates.
   *
   * @return the harness's own id for it
   */
  String createSession(HarnessEndpoint endpoint, String directory, String title);

  /** Deletes the session and its directory; one already gone is not an error. */
  void deleteSession(HarnessEndpoint endpoint, String directory, String sessionId);

  List<ChatMessage> messages(HarnessEndpoint endpoint, String directory, String sessionId);

  /** Starts a reply and returns at once; the reply arrives on {@link #stream}. */
  void prompt(HarnessEndpoint endpoint, String directory, String sessionId, String text);

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
