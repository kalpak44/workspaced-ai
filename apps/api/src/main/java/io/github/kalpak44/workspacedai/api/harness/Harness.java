package io.github.kalpak44.workspacedai.api.harness;

import io.github.kalpak44.workspacedai.api.runtime.WorkspaceTerminal;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
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

  /**
   * Where the workspace terminal opens and whole-harness requests resolve, and the shared project
   * of sessions created before each had its own.
   */
  String PROJECT_ROOT = HOME + "/project";

  /** Each session's own folder is made here; OpenCode treats every folder as a separate project. */
  String SESSIONS_ROOT = HOME + "/sessions";

  /**
   * The user's own instructions: every Markdown file here is given to the agent, after the image's
   * read-only guide. The user edits them in the app; the agent can edit them too.
   */
  String INSTRUCTIONS_DIRECTORY = HOME + "/.config/workspaced/instructions";

  /**
   * The workspace's scheduled tasks: each Markdown file here has the agent run its prompt, in a new
   * session, whenever its cron expression matches. The user edits them in the app; the agent adds
   * them when asked to do something later or regularly.
   */
  String TASKS_DIRECTORY = HOME + "/.config/workspaced/tasks";

  /**
   * The workspace's variables, as NAME=value lines, which the harness sets when it starts. The user
   * edits them in the app; the agent can edit them too.
   */
  String VARIABLES_FILE = HOME + "/.config/workspaced/.env";

  /**
   * Where the harness's backup tool takes its job and reports its progress; the tool leaves this
   * folder out of every archive it makes.
   */
  String BACKUP_DIRECTORY = HOME + "/.cache/workspaced/backup";

  /**
   * A restore the API asks for: the archive's URL and an id the harness records once it has
   * restored, so a later start does not restore again. No user secret may take either name.
   */
  String RESTORE_URL_VARIABLE = "WORKSPACED_RESTORE_URL";

  String RESTORE_ID_VARIABLE = "WORKSPACED_RESTORE_ID";

  /** An instruction or task file's name, without ".md": a plain file name, never a path. */
  String INSTRUCTION_NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9_-]{0,62}";

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

  /** Creates a session's folder, which OpenCode needs to exist before it can work there. */
  void createDirectory(HarnessEndpoint endpoint, String directory);

  /**
   * Stops what OpenCode runs for a session's folder and removes it with its files. Only a folder
   * directly under {@link #SESSIONS_ROOT} is accepted, so no other path can be removed.
   */
  void removeDirectory(HarnessEndpoint endpoint, String directory);

  List<ChatMessage> messages(HarnessEndpoint endpoint, String directory, String sessionId);

  List<AgentForm> forms(HarnessEndpoint endpoint, String sessionId);

  void replyForm(
      HarnessEndpoint endpoint, String sessionId, String formId, Map<String, Object> answer);

  void cancelForm(HarnessEndpoint endpoint, String sessionId, String formId);

  /** The name the harness gave the session, or empty while it still has its placeholder. */
  Optional<String> title(HarnessEndpoint endpoint, String directory, String sessionId);

  List<HarnessAgent> agents(HarnessEndpoint endpoint, String directory);

  /** Null while OpenCode has not yet selected an agent for the session. */
  String sessionAgent(HarnessEndpoint endpoint, String sessionId);

  /** Changes only the agent used by future turns, preserving the selected model and history. */
  void switchAgent(HarnessEndpoint endpoint, String sessionId, String agentId);

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

  /**
   * Adds or replaces MCP servers in the harness's global configuration, which every session's
   * project reads, and reloads it. They outlast a restart, since the configuration is in the home.
   * The file is read and written back whole, so two changes to one workspace must not overlap; the
   * caller makes them take turns, as does {@code removeMcp}'s.
   *
   * @return whether anything had to change
   */
  boolean configureMcp(HarnessEndpoint endpoint, Map<String, McpServerConfig> servers);

  /** The names of the workspace's instruction files, without ".md", in name order. */
  List<String> instructionFiles(HarnessEndpoint endpoint);

  /** One instruction file's Markdown; empty when there is no such file. */
  Optional<String> readInstructions(HarnessEndpoint endpoint, String name);

  /**
   * Creates or replaces an instruction file. A replaced one applies from the next message in every
   * session; a new one in new sessions, and in those already open once the workspace restarts.
   */
  void writeInstructions(HarnessEndpoint endpoint, String name, String content);

  void deleteInstructions(HarnessEndpoint endpoint, String name);

  /** The variables file's text; empty when there is none yet. */
  Optional<String> readVariables(HarnessEndpoint endpoint);

  /** Replaces the variables file whole; it applies when the workspace next starts. */
  void writeVariables(HarnessEndpoint endpoint, String content);

  /** The names of the workspace's task files, without ".md", in name order. */
  List<String> taskFiles(HarnessEndpoint endpoint);

  /** One task file's text, front matter included; empty when there is no such file. */
  Optional<String> readTask(HarnessEndpoint endpoint, String name);

  void writeTask(HarnessEndpoint endpoint, String name, String content);

  void deleteTask(HarnessEndpoint endpoint, String name);

  /**
   * Starts archiving the home and uploading it to that URL, and returns at once; follow it with
   * {@link #backupStatus}.
   *
   * @return false when the harness image predates backups, so nothing was started
   */
  boolean startBackup(HarnessEndpoint endpoint, String backupId, URI uploadUrl);

  /** The backup tool's latest report, as JSON; empty before it has written one. */
  Optional<String> backupStatus(HarnessEndpoint endpoint);

  /**
   * Writes the stream to that absolute path, creating its folder, without holding it in memory.
   *
   * @param length the stream's exact length in bytes
   */
  void uploadFile(HarnessEndpoint endpoint, String path, InputStream content, long length);

  /** A file under the directory, streamed; empty when there is no such file. */
  Optional<HarnessFile> downloadFile(
      HarnessEndpoint endpoint, String directory, String relativePath);

  /** A file being read from the workspace; closing it releases the connection. */
  record HarnessFile(InputStream content, long length) {}

  /** An MCP server the harness reaches over HTTP, or one it starts in the workspace. */
  sealed interface McpServerConfig {

    /** A header value may be {@code {env:NAME}}, filled from the pod. */
    record Remote(String url, Map<String, String> headers) implements McpServerConfig {}

    /**
     * Started in each project that uses it. It inherits the pod's environment, secrets included; an
     * {@code environment} value may be {@code {env:NAME}}, filled from the pod.
     */
    record Local(List<String> command, Map<String, String> environment)
        implements McpServerConfig {}
  }

  /** Stops the reply in progress, if there is one. */
  void abort(HarnessEndpoint endpoint, String directory, String sessionId);

  /** Opens a Bash PTY through OpenCode and streams its output. */
  WorkspaceTerminal terminal(
      HarnessEndpoint endpoint, String directory, OutputStream output, Runnable onExit);

  List<TerminalSession> terminals(HarnessEndpoint endpoint, String directory);

  TerminalSession createTerminal(HarnessEndpoint endpoint, String directory, String title);

  Optional<TerminalSession> terminalInfo(HarnessEndpoint endpoint, String directory, String id);

  /** Terminates the process and removes its retained metadata. Missing terminals are ignored. */
  void deleteTerminal(HarnessEndpoint endpoint, String directory, String id);

  /** Attaches to an existing PTY. Closing this handle only detaches the browser. */
  WorkspaceTerminal connectTerminal(
      HarnessEndpoint endpoint, String directory, String id, OutputStream output, Runnable onExit);

  /**
   * Follows the workspace-wide V2 event stream until the returned handle is closed or the harness
   * ends it. Callers fan its envelopes out to their session subscribers.
   */
  AutoCloseable stream(
      HarnessEndpoint endpoint, Consumer<HarnessEventEnvelope> onEvent, Runnable onEnd);

  /**
   * Removes one MCP server, from the global configuration or else from the runtime set servers
   * added before it held them. Missing servers are ignored.
   */
  void removeMcp(HarnessEndpoint endpoint, String name);
}
