package io.github.kalpak44.workspacedai.api.harness;

import io.github.kalpak44.workspacedai.api.runtime.WorkspaceTerminal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Talks only to the OpenCode V2 HTTP API. */
@Component
public class OpenCodeHarness implements Harness {

  private static final Logger LOG = LoggerFactory.getLogger(OpenCodeHarness.class);
  private static final String USER = "opencode";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration SHELL_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration SHELL_POLL = Duration.ofMillis(200);
  // A file moves at the speed of the user's connection, not the harness's.
  private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);
  private static final String CONFIG_DIRECTORY = HOME + "/.config/opencode";
  private static final String MCP_CONFIG = "opencode.jsonc";
  private static final String MARKDOWN = ".md";
  private static final Pattern INSTRUCTION_NAME = Pattern.compile(INSTRUCTION_NAME_PATTERN);
  private static final String EVENT_TYPES =
      String.join(
          ",",
          "session.renamed",
          "session.inbox.delivered",
          "session.revert.committed",
          "session.execution.started",
          "session.execution.succeeded",
          "session.execution.failed",
          "session.execution.interrupted",
          "session.text.started",
          "session.text.delta",
          "session.text.ended",
          "session.reasoning.started",
          "session.reasoning.delta",
          "session.reasoning.ended",
          "session.tool.input.started",
          "session.tool.called",
          "session.tool.progress",
          "session.tool.success",
          "session.tool.failed",
          "form.created",
          "form.replied",
          "form.cancelled");

  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(CONNECT_TIMEOUT)
          .build();
  private final JsonMapper json;

  public OpenCodeHarness(JsonMapper json) {
    this.json = json;
  }

  @Override
  public void validate(HarnessEndpoint endpoint, String directory) {
    var info = call(endpoint, "GET", "/api/info", null);
    var version = text(info, "version");
    if (version == null || !version.startsWith("2.")) {
      throw new HarnessUnavailableException("the workspace is not running OpenCode V2");
    }
    call(endpoint, "GET", withLocation("/api/location", directory), null);
  }

  /**
   * The cursor anchors on each session's last update, so a session that changes while the pages are
   * read comes back on a later page. The later copy wins, and a page with nothing new ends it.
   */
  @Override
  public List<HarnessSession> sessions(HarnessEndpoint endpoint) {
    var result = new LinkedHashMap<String, HarnessSession>();
    String cursor = null;
    do {
      var path = "/api/session?limit=100&parentID=null";
      if (cursor != null) {
        path += "&cursor=" + encode(cursor);
      } else {
        path += "&order=asc";
      }
      var page = call(endpoint, "GET", path, null);
      var fresh = false;
      for (var session : page.path("data")) {
        var created = number(session.path("time"), "created");
        var updated = number(session.path("time"), "updated");
        var id = text(session, "id");
        fresh |=
            result.put(
                    id,
                    new HarnessSession(
                        id,
                        text(session, "title"),
                        text(session.path("location"), "directory"),
                        created == null ? Instant.now() : instant(created),
                        updated == null ? null : instant(updated)))
                == null;
      }
      cursor = fresh ? nullableText(page.path("cursor"), "next") : null;
    } while (cursor != null);
    return List.copyOf(result.values());
  }

  @Override
  public String createSession(HarnessEndpoint endpoint, String directory, String title) {
    var body = new LinkedHashMap<String, Object>();
    body.put("location", Map.of("directory", directory));
    if (title != null) {
      body.put("title", title);
    }
    return text(call(endpoint, "POST", "/api/session", body).path("data"), "id");
  }

  @Override
  public List<HarnessAgent> agents(HarnessEndpoint endpoint, String directory) {
    var result = new ArrayList<HarnessAgent>();
    for (var agent :
        call(endpoint, "GET", withLocation("/api/agent", directory), null).path("data")) {
      result.add(
          new HarnessAgent(
              text(agent, "id"),
              text(agent, "name"),
              text(agent, "description"),
              text(agent, "mode"),
              agent.path("hidden").asBoolean()));
    }
    return List.copyOf(result);
  }

  @Override
  public String sessionAgent(HarnessEndpoint endpoint, String sessionId) {
    return text(
        call(endpoint, "GET", "/api/session/" + encode(sessionId), null).path("data"), "agent");
  }

  @Override
  public void switchAgent(HarnessEndpoint endpoint, String sessionId, String agentId) {
    call(
        endpoint, "POST", "/api/session/" + encode(sessionId) + "/agent", Map.of("agent", agentId));
  }

  @Override
  public void deleteSession(HarnessEndpoint endpoint, String directory, String sessionId) {
    var response = send(request(endpoint, "/api/session/" + encode(sessionId)).DELETE());
    if (response.statusCode() != 404) {
      check(response);
    }
  }

  @Override
  public void createDirectory(HarnessEndpoint endpoint, String directory) {
    shell(endpoint, "mkdir -p -- " + quote(directory));
  }

  @Override
  public void removeDirectory(HarnessEndpoint endpoint, String directory) {
    var path = Path.of(directory).normalize();
    if (!path.isAbsolute() || !Path.of(SESSIONS_ROOT).equals(path.getParent())) {
      throw new IllegalArgumentException("not a session directory: " + directory);
    }
    // Its MCP servers and other services keep running until the project is let go.
    send(request(endpoint, withLocation("/api/debug/location", path.toString())).DELETE());
    shell(endpoint, "rm -rf -- " + quote(path.toString()));
  }

  /**
   * The job goes in a file rather than on the command line, which keeps the signed URL out of the
   * process list. The tool runs in a session of its own so it outlives the shell that starts it.
   */
  @Override
  public boolean startBackup(HarnessEndpoint endpoint, String backupId, URI uploadUrl) {
    if (shellExit(endpoint, "command -v workspace-backup") != 0) {
      return false;
    }
    var directory = quote(BACKUP_DIRECTORY);
    shell(endpoint, "mkdir -p -- " + directory + " && rm -f -- " + directory + "/status.json");
    writeFile(
        endpoint,
        BACKUP_DIRECTORY + "/job.json",
        json.writeValueAsString(Map.of("id", backupId, "uploadUrl", uploadUrl.toString())));
    shell(endpoint, "setsid workspace-backup run > " + directory + "/log 2>&1 < /dev/null &");
    return true;
  }

  @Override
  public Optional<String> backupStatus(HarnessEndpoint endpoint) {
    return readFile(endpoint, BACKUP_DIRECTORY, "status.json");
  }

  @Override
  public boolean enableWebServer(HarnessEndpoint endpoint, URI url) {
    if (shellExit(endpoint, "command -v web-server") != 0) {
      return false;
    }
    shell(endpoint, "web-server enable " + quote(url.toString()));
    return true;
  }

  /** An image without the tool never started a web server, so there is nothing to stop. */
  @Override
  public void disableWebServer(HarnessEndpoint endpoint) {
    shell(endpoint, "! command -v web-server > /dev/null || web-server disable");
  }

  @Override
  public Optional<String> webServerRequest(HarnessEndpoint endpoint) {
    return readFile(endpoint, WEB_DIRECTORY, "request");
  }

  @Override
  public void answerWebServerRequest(HarnessEndpoint endpoint, String result) {
    writeFile(endpoint, WEB_DIRECTORY + "/result", result + "\n");
    shell(endpoint, "rm -f -- " + quote(WEB_DIRECTORY + "/request"));
  }

  @Override
  public void uploadFile(HarnessEndpoint endpoint, String path, InputStream content, long length) {
    shell(endpoint, "mkdir -p -- " + quote(Path.of(path).getParent().toString()));
    var request =
        request(endpoint, "/api/experimental/fs/write?path=" + encode(path))
            .timeout(TRANSFER_TIMEOUT)
            .header("Content-Type", "application/octet-stream")
            .POST(
                BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(() -> content), length));
    check(send(request));
  }

  @Override
  public Optional<HarnessFile> downloadFile(
      HarnessEndpoint endpoint, String directory, String relativePath) {
    var segments =
        Arrays.stream(relativePath.split("/"))
            .map(segment -> encode(segment).replace("+", "%20"))
            .collect(Collectors.joining("/"));
    var request =
        request(endpoint, withLocation("/api/fs/read/" + segments, directory))
            .timeout(TRANSFER_TIMEOUT)
            .GET()
            .build();
    HttpResponse<InputStream> response;
    try {
      response = http.send(request, BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw new HarnessUnavailableException("the workspace's OpenCode service is not answering", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HarnessUnavailableException("interrupted calling OpenCode", e);
    }
    if (response.statusCode() == 404) {
      close(response.body());
      return Optional.empty();
    }
    if (response.statusCode() / 100 != 2) {
      close(response.body());
      throw new HarnessUnavailableException("OpenCode V2 answered " + response.statusCode());
    }
    var length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
    return Optional.of(new HarnessFile(response.body(), length));
  }

  private static void close(InputStream stream) {
    try {
      stream.close();
    } catch (IOException e) {
      LOG.debug("could not close a response from OpenCode", e);
    }
  }

  /** Runs one command to completion, failing unless it exits with 0. */
  private void shell(HarnessEndpoint endpoint, String command) {
    if (shellExit(endpoint, command) != 0) {
      throw new HarnessUnavailableException("the workspace could not run: " + command);
    }
  }

  /** Runs one command to completion and answers its exit code. */
  private int shellExit(HarnessEndpoint endpoint, String command) {
    var info =
        call(
                endpoint,
                "POST",
                withLocation("/api/shell", PROJECT_ROOT),
                Map.of("command", command, "timeout", SHELL_TIMEOUT.toMillis()))
            .path("data");
    var id = text(info, "id");
    var deadline = Instant.now().plus(SHELL_TIMEOUT).plus(CONNECT_TIMEOUT);
    while ("running".equals(text(info, "status"))) {
      if (Instant.now().isAfter(deadline)) {
        throw new HarnessUnavailableException("the workspace did not finish: " + command);
      }
      try {
        Thread.sleep(SHELL_POLL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new HarnessUnavailableException("interrupted waiting for: " + command, e);
      }
      info =
          call(endpoint, "GET", withLocation("/api/shell/" + encode(id), PROJECT_ROOT), null)
              .path("data");
    }
    var exit = number(info, "exit");
    if (!"exited".equals(text(info, "status")) || exit == null) {
      throw new HarnessUnavailableException("the workspace could not run: " + command);
    }
    return exit.intValue();
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  @Override
  public List<ChatMessage> messages(HarnessEndpoint endpoint, String directory, String sessionId) {
    var result = new ArrayList<ChatMessage>();
    String cursor = null;
    do {
      var path = "/api/session/" + encode(sessionId) + "/message?limit=100";
      if (cursor != null) {
        path += "&cursor=" + encode(cursor);
      } else {
        path += "&order=asc";
      }
      var page = call(endpoint, "GET", path, null);
      for (var message : page.path("data")) {
        var mapped = message(message);
        if (mapped != null) {
          result.add(mapped);
        }
      }
      cursor = nullableText(page.path("cursor"), "next");
    } while (cursor != null);
    return result;
  }

  @Override
  public Optional<String> title(HarnessEndpoint endpoint, String directory, String sessionId) {
    var response = call(endpoint, "GET", "/api/session/" + encode(sessionId), null);
    return Optional.ofNullable(text(response.path("data"), "title"));
  }

  @Override
  public List<AgentForm> forms(HarnessEndpoint endpoint, String sessionId) {
    var result = new ArrayList<AgentForm>();
    for (var form :
        call(endpoint, "GET", "/api/session/" + encode(sessionId) + "/form", null).path("data")) {
      result.add(new AgentForm(text(form, "id"), text(form, "title"), form.path("fields")));
    }
    return result;
  }

  @Override
  public void replyForm(
      HarnessEndpoint endpoint, String sessionId, String formId, Map<String, Object> answer) {
    call(
        endpoint,
        "POST",
        "/api/session/" + encode(sessionId) + "/form/" + encode(formId) + "/reply",
        Map.of("answer", answer));
  }

  @Override
  public void cancelForm(HarnessEndpoint endpoint, String sessionId, String formId) {
    call(endpoint, "DELETE", "/api/session/" + encode(sessionId) + "/form/" + encode(formId), null);
  }

  @Override
  public void prompt(
      HarnessEndpoint endpoint, String directory, String sessionId, String text, ModelRef model) {
    if (model != null) {
      call(
          endpoint,
          "POST",
          "/api/session/" + encode(sessionId) + "/model",
          Map.of("model", Map.of("providerID", model.providerId(), "id", model.modelId())));
    }
    call(endpoint, "POST", "/api/session/" + encode(sessionId) + "/prompt", Map.of("text", text));
  }

  @Override
  public void editMessage(
      HarnessEndpoint endpoint,
      String directory,
      String sessionId,
      String messageId,
      String text,
      ModelRef model) {
    var path = "/api/session/" + encode(sessionId);
    if (model != null) {
      call(
          endpoint,
          "POST",
          path + "/model",
          Map.of("model", Map.of("providerID", model.providerId(), "id", model.modelId())));
    }
    call(endpoint, "POST", path + "/revert/stage", Map.of("messageID", messageId, "files", false));
    call(endpoint, "POST", path + "/revert/commit", Map.of());
    prompt(endpoint, directory, sessionId, text, null);
  }

  @Override
  public ModelCatalogue models(HarnessEndpoint endpoint, String providerId) {
    var response = call(endpoint, "GET", withLocation("/api/model", PROJECT_ROOT), null);
    var models = new ArrayList<ModelOption>();
    for (var model : response.path("data")) {
      if (!providerId.equals(text(model, "providerID"))
          || !model.path("enabled").asBoolean(true)
          || !model.path("capabilities").path("tools").asBoolean(false)) {
        continue;
      }
      var costs = model.path("cost");
      var cost = costs.isArray() && !costs.isEmpty() ? costs.get(0) : json.missingNode();
      models.add(
          new ModelOption(
              text(model, "id"),
              text(model, "name"),
              longNumber(model.path("limit"), "context"),
              number(cost, "input"),
              number(cost, "output")));
    }
    models.sort(Comparator.comparing(ModelOption::name, String.CASE_INSENSITIVE_ORDER));
    var defaultModel =
        call(endpoint, "GET", withLocation("/api/model/default", PROJECT_ROOT), null);
    return new ModelCatalogue(text(defaultModel.path("data"), "id"), models);
  }

  @Override
  public Map<String, String> mcpStatus(HarnessEndpoint endpoint) {
    var statuses = new TreeMap<String, String>();
    for (var server :
        call(endpoint, "GET", withLocation("/api/mcp", PROJECT_ROOT), null).path("data")) {
      statuses.put(text(server, "name"), text(server.path("status"), "status"));
    }
    return statuses;
  }

  /**
   * The servers go into a global configuration file of their own: {@code start.sh} replaces the
   * image's {@code opencode.json} on every start but leaves this one, which OpenCode reads on top
   * of it. Reloading applies it to every loaded project; it also cancels pending permission and
   * form requests, which sessions see as cancelled.
   */
  @Override
  public boolean configureMcp(HarnessEndpoint endpoint, Map<String, McpServerConfig> servers) {
    if (servers.isEmpty()) {
      return false;
    }
    var config = readConfig(endpoint);
    var configured = config.withObjectProperty("mcp").withObjectProperty("servers");
    servers.forEach((name, server) -> configured.set(name, json.valueToTree(mcpConfig(server))));
    writeConfig(endpoint, config);
    reload(endpoint);
    return true;
  }

  static Map<String, Object> mcpConfig(McpServerConfig server) {
    return switch (server) {
      case McpServerConfig.Remote remote ->
          Map.of("type", "remote", "url", remote.url(), "headers", remote.headers());
      case McpServerConfig.Local local ->
          Map.of("type", "local", "command", local.command(), "environment", local.environment());
    };
  }

  @Override
  public void removeMcp(HarnessEndpoint endpoint, String name) {
    var config = readConfig(endpoint);
    if (config.path("mcp").path("servers") instanceof ObjectNode configured
        && configured.has(name)) {
      configured.remove(name);
      writeConfig(endpoint, config);
      reload(endpoint);
      return;
    }
    call(
        endpoint,
        "DELETE",
        withLocation("/api/experimental/mcp/" + encode(name), PROJECT_ROOT),
        null);
  }

  @Override
  public List<String> instructionFiles(HarnessEndpoint endpoint) {
    return markdownFiles(endpoint, INSTRUCTIONS_DIRECTORY);
  }

  /** The names, without ".md", of the plainly named Markdown files in the directory. */
  private List<String> markdownFiles(HarnessEndpoint endpoint, String directory) {
    var response = send(request(endpoint, withLocation("/api/fs/list", directory)).GET());
    if (response.statusCode() == 404) {
      return List.of();
    }
    var names = new ArrayList<String>();
    for (var entry : json.readTree(check(response).body()).path("data")) {
      var path = entry.path("path").asString("");
      if ("file".equals(entry.path("type").asString(""))
          && path.endsWith(MARKDOWN)
          && INSTRUCTION_NAME
              .matcher(path.substring(0, path.length() - MARKDOWN.length()))
              .matches()) {
        names.add(path.substring(0, path.length() - MARKDOWN.length()));
      }
    }
    names.sort(null);
    return names;
  }

  @Override
  public Optional<String> readInstructions(HarnessEndpoint endpoint, String name) {
    return readFile(endpoint, INSTRUCTIONS_DIRECTORY, instructionFile(name));
  }

  /**
   * OpenCode reads the instruction files, their contents included, when it loads a project, so a
   * save or a deleted reaches no session already open, and none in a shared folder such as the
   * project root, until the projects reload. The reload cancels questions the agent is waiting on,
   * the price of instructions that take effect on the next message.
   */
  @Override
  public void writeInstructions(HarnessEndpoint endpoint, String name, String content) {
    writeMarkdown(endpoint, INSTRUCTIONS_DIRECTORY, name, content);
    reload(endpoint);
  }

  @Override
  public void deleteInstructions(HarnessEndpoint endpoint, String name) {
    shell(endpoint, "rm -f -- " + quote(INSTRUCTIONS_DIRECTORY + "/" + instructionFile(name)));
    reload(endpoint);
  }

  /**
   * The reload answers before the projects are rebuilt; reading the project root waits for that, so
   * a message sent right after it already runs with the new configuration.
   */
  private void reload(HarnessEndpoint endpoint) {
    call(endpoint, "POST", "/api/location/reload", null);
    call(endpoint, "GET", withLocation("/api/location", PROJECT_ROOT), null);
  }

  @Override
  public Optional<String> readVariables(HarnessEndpoint endpoint) {
    var file = Path.of(VARIABLES_FILE);
    return readFile(endpoint, file.getParent().toString(), file.getFileName().toString());
  }

  @Override
  public void writeVariables(HarnessEndpoint endpoint, String content) {
    shell(endpoint, "mkdir -p -- " + quote(Path.of(VARIABLES_FILE).getParent().toString()));
    writeFile(endpoint, VARIABLES_FILE, content);
  }

  @Override
  public List<String> taskFiles(HarnessEndpoint endpoint) {
    return markdownFiles(endpoint, TASKS_DIRECTORY);
  }

  @Override
  public Optional<String> readTask(HarnessEndpoint endpoint, String name) {
    return readFile(endpoint, TASKS_DIRECTORY, instructionFile(name));
  }

  @Override
  public void writeTask(HarnessEndpoint endpoint, String name, String content) {
    writeMarkdown(endpoint, TASKS_DIRECTORY, name, content);
  }

  @Override
  public void deleteTask(HarnessEndpoint endpoint, String name) {
    shell(endpoint, "rm -f -- " + quote(TASKS_DIRECTORY + "/" + instructionFile(name)));
  }

  private void writeMarkdown(
      HarnessEndpoint endpoint, String directory, String name, String content) {
    var file = instructionFile(name);
    // A workspace from before the folder existed has none yet.
    if (!markdownFiles(endpoint, directory).contains(name)) {
      shell(endpoint, "mkdir -p -- " + quote(directory));
    }
    writeFile(endpoint, directory + "/" + file, content);
  }

  /** Refuses any name that is not a plain file name, so no other path can be reached. */
  private static String instructionFile(String name) {
    if (name == null || !INSTRUCTION_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("not an instruction or task file name: " + name);
    }
    return name + MARKDOWN;
  }

  /** The file's text, or empty when it does not exist. */
  private Optional<String> readFile(HarnessEndpoint endpoint, String directory, String name) {
    var response = send(request(endpoint, withLocation("/api/fs/read/" + name, directory)).GET());
    return response.statusCode() == 404 ? Optional.empty() : Optional.of(check(response).body());
  }

  private void writeFile(HarnessEndpoint endpoint, String path, String content) {
    check(
        send(
            request(endpoint, "/api/experimental/fs/write?path=" + encode(path))
                .header("Content-Type", "application/octet-stream")
                .POST(BodyPublishers.ofString(content))));
  }

  /** An object, empty while the file does not exist yet. */
  private ObjectNode readConfig(HarnessEndpoint endpoint) {
    var response =
        send(request(endpoint, withLocation("/api/fs/read/" + MCP_CONFIG, CONFIG_DIRECTORY)).GET());
    if (response.statusCode() == 404) {
      return json.createObjectNode();
    }
    if (check(response).body().isBlank()) {
      return json.createObjectNode();
    }
    if (json.readTree(response.body()) instanceof ObjectNode config) {
      return config;
    }
    throw new HarnessUnavailableException(
        CONFIG_DIRECTORY + "/" + MCP_CONFIG + " is not an object");
  }

  private void writeConfig(HarnessEndpoint endpoint, ObjectNode config) {
    writeFile(
        endpoint,
        CONFIG_DIRECTORY + "/" + MCP_CONFIG,
        json.writerWithDefaultPrettyPrinter().writeValueAsString(config));
  }

  @Override
  public void abort(HarnessEndpoint endpoint, String directory, String sessionId) {
    call(endpoint, "POST", "/api/session/" + encode(sessionId) + "/interrupt", Map.of());
  }

  @Override
  public WorkspaceTerminal terminal(
      HarnessEndpoint endpoint, String directory, OutputStream output, Runnable onExit) {
    var created = createTerminal(endpoint, directory, "Workspace terminal");
    WorkspaceTerminal connection;
    try {
      connection = connectTerminal(endpoint, directory, created.id(), output, onExit);
    } catch (RuntimeException error) {
      deleteTerminal(endpoint, directory, created.id());
      throw error;
    }
    return new WorkspaceTerminal() {
      public void input(byte[] bytes) throws IOException {
        connection.input(bytes);
      }

      public void resize(int columns, int rows) {
        connection.resize(columns, rows);
      }

      public void close() {
        try {
          connection.close();
        } finally {
          deleteTerminal(endpoint, directory, created.id());
        }
      }
    };
  }

  @Override
  public List<TerminalSession> terminals(HarnessEndpoint endpoint, String directory) {
    var result = new ArrayList<TerminalSession>();
    for (var item : call(endpoint, "GET", withLocation("/api/pty", directory), null).path("data")) {
      result.add(terminalSession(item));
    }
    return List.copyOf(result);
  }

  @Override
  public Optional<TerminalSession> terminalInfo(
      HarnessEndpoint endpoint, String directory, String id) {
    var response = send(request(endpoint, withLocation("/api/pty/" + encode(id), directory)).GET());
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    return Optional.of(terminalSession(json.readTree(check(response).body()).path("data")));
  }

  @Override
  public void deleteTerminal(HarnessEndpoint endpoint, String directory, String id) {
    var response =
        send(request(endpoint, withLocation("/api/pty/" + encode(id), directory)).DELETE());
    if (response.statusCode() != 404) {
      check(response);
    }
  }

  private static TerminalSession terminalSession(JsonNode node) {
    return new TerminalSession(
        text(node, "id"),
        text(node, "title"),
        text(node, "command"),
        text(node, "cwd"),
        text(node, "status"),
        node.path("pid").asLong(),
        node.path("exitCode").isNumber() ? node.path("exitCode").asInt() : null);
  }

  @Override
  public TerminalSession createTerminal(HarnessEndpoint endpoint, String directory, String title) {
    validate(endpoint, directory);
    var created =
        call(
                endpoint,
                "POST",
                withLocation("/api/pty", directory),
                Map.of(
                    "command",
                    "/bin/bash",
                    "args",
                    List.of("-l"),
                    "cwd",
                    directory,
                    "title",
                    title))
            .path("data");
    return terminalSession(created);
  }

  @Override
  public WorkspaceTerminal connectTerminal(
      HarnessEndpoint endpoint,
      String directory,
      String ptyId,
      OutputStream output,
      Runnable onExit) {
    var tokenResponse =
        check(
            send(
                request(
                        endpoint,
                        withLocation("/api/pty/" + encode(ptyId) + "/connect-token", directory))
                    .header("x-opencode-ticket", "1")
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString("{}"))));
    var ticket = text(json.readTree(tokenResponse.body()).path("data"), "ticket");
    var socketUri =
        websocket(
            endpoint.baseUrl(),
            withLocation("/api/pty/" + encode(ptyId) + "/connect", directory)
                + "&ticket="
                + encode(ticket));
    var closed = new AtomicBoolean();
    WebSocket socket;
    try {
      socket =
          http.newWebSocketBuilder()
              .connectTimeout(CONNECT_TIMEOUT)
              .buildAsync(socketUri, new TerminalListener(output, closed, onExit))
              .join();
    } catch (RuntimeException error) {
      throw new HarnessUnavailableException("could not connect to the terminal", error);
    }
    return new WorkspaceTerminal() {
      @Override
      public void input(byte[] bytes) {
        socket.sendBinary(ByteBuffer.wrap(bytes), true).join();
      }

      @Override
      public void resize(int columns, int rows) {
        call(
            endpoint,
            "PUT",
            withLocation("/api/pty/" + encode(ptyId), directory),
            Map.of("size", Map.of("cols", columns, "rows", rows)));
      }

      @Override
      public void close() {
        if (closed.compareAndSet(false, true)) {
          socket.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
        }
      }
    };
  }

  @Override
  public AutoCloseable stream(
      HarnessEndpoint endpoint, Consumer<HarnessEventEnvelope> onEvent, Runnable onEnd) {
    var closed = new AtomicBoolean();
    var body = new AtomicReference<InputStream>();
    var request =
        request(endpoint, "/api/event?types=" + EVENT_TYPES.replace(",", "&types="))
            .timeout(Duration.ofDays(1))
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    var reader =
        Thread.ofVirtual()
            .name("opencode-v2-events")
            .start(
                () -> {
                  try {
                    var response = http.send(request, BodyHandlers.ofInputStream());
                    body.set(response.body());
                    if (response.statusCode() / 100 != 2) {
                      throw new IOException("event stream answered " + response.statusCode());
                    }
                    read(response.body(), onEvent, closed);
                  } catch (IOException e) {
                    if (!closed.get()) {
                      LOG.info("OpenCode V2 event stream ended: {}", e.getMessage());
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    if (!closed.get()) {
                      onEnd.run();
                    }
                  }
                });
    return () -> {
      if (closed.compareAndSet(false, true)) {
        var stream = body.get();
        if (stream != null) {
          stream.close();
        }
        reader.interrupt();
      }
    };
  }

  private void read(InputStream body, Consumer<HarnessEventEnvelope> onEvent, AtomicBoolean closed)
      throws IOException {
    var tools = new TreeMap<String, ChatPart>();
    try (var lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while (!closed.get() && (line = lines.readLine()) != null) {
        if (line.startsWith(":")) {
          onEvent.accept(new HarnessEventEnvelope(null, new HarnessEvent.Heartbeat()));
        } else if (line.startsWith("data:")) {
          events(json.readTree(line.substring(5)), tools).forEach(onEvent);
        }
      }
    }
  }

  /**
   * @param tools the tool parts of this stream still running, by id: a part replaces the whole
   *     state the browser holds, so later events carry forward what earlier ones said
   */
  static List<HarnessEventEnvelope> events(JsonNode event, Map<String, ChatPart> tools) {
    var type = text(event, "type");
    var data = event.path("data");
    var sessionId = text("form.created".equals(type) ? data.path("form") : data, "sessionID");
    if (type == null || sessionId == null) {
      return List.of();
    }
    var result = new ArrayList<HarnessEvent>();
    switch (type) {
      case "form.created", "form.replied", "form.cancelled" -> result.add(new HarnessEvent.Forms());
      case "session.renamed" -> result.add(new HarnessEvent.Title(text(data, "title")));
      case "session.inbox.delivered", "session.revert.committed" ->
          result.add(new HarnessEvent.Refresh());
      case "session.execution.started" -> result.add(new HarnessEvent.Status(true));
      case "session.execution.succeeded", "session.execution.interrupted" ->
          result.add(new HarnessEvent.Status(false));
      case "session.execution.failed" -> {
        result.add(new HarnessEvent.Error(error(data.path("error"))));
        result.add(new HarnessEvent.Status(false));
      }
      case "session.text.started", "session.reasoning.started" -> {
        var messageId = text(data, "assistantMessageID");
        var partType = type.contains("reasoning") ? "reasoning" : "text";
        result.add(new HarnessEvent.Message(messageId, "assistant"));
        result.add(
            new HarnessEvent.Part(
                messageId, new ChatPart(partId(partType, data), partType, "", null, null)));
      }
      case "session.text.delta", "session.reasoning.delta" -> {
        var partType = type.contains("reasoning") ? "reasoning" : "text";
        result.add(
            new HarnessEvent.Delta(
                text(data, "assistantMessageID"), partId(partType, data), text(data, "delta")));
      }
      case "session.text.ended", "session.reasoning.ended" -> {
        var partType = type.contains("reasoning") ? "reasoning" : "text";
        result.add(
            new HarnessEvent.Part(
                text(data, "assistantMessageID"),
                new ChatPart(partId(partType, data), partType, text(data, "text"), null, null)));
      }
      case "session.tool.input.started" -> {
        var id = text(data, "id");
        var name = text(data, "name");
        var part = new ChatPart(id, "tool", name, name, "pending");
        tools.put(id, part);
        result.add(new HarnessEvent.Part(text(data, "assistantMessageID"), part));
      }
      case "session.tool.called", "session.tool.progress" -> {
        var id = text(data, "id");
        var input = data.path("input").isObject() ? data.path("input") : null;
        var part = tool(tools.get(id), id).with("running", input, null);
        tools.put(id, part);
        result.add(new HarnessEvent.Part(text(data, "assistantMessageID"), part));
      }
      case "session.tool.success", "session.tool.failed" -> {
        var id = text(data, "id");
        var failed = type.endsWith("failed");
        var output = output(data.path("content"), failed ? data.path("error") : null);
        var part = tool(tools.remove(id), id).with(failed ? "error" : "completed", null, output);
        result.add(new HarnessEvent.Part(text(data, "assistantMessageID"), part));
      }
      default -> {
        return List.of();
      }
    }
    return result.stream().map(e -> new HarnessEventEnvelope(sessionId, e)).toList();
  }

  static ChatMessage message(JsonNode message) {
    var id = text(message, "id");
    var type = text(message, "type");
    if ("user".equals(type)) {
      return new ChatMessage(
          id,
          "user",
          List.of(new ChatPart(id + ":text", "text", text(message, "text"), null, null)));
    }
    if (!"assistant".equals(type)) {
      return null;
    }
    var parts = new ArrayList<ChatPart>();
    var ordinal = 0;
    for (var content : message.path("content")) {
      var mapped = part(id, ordinal++, content);
      if (mapped != null) {
        parts.add(mapped);
      }
    }
    return new ChatMessage(id, "assistant", parts);
  }

  private static ChatPart part(String messageId, int ordinal, JsonNode content) {
    var type = text(content, "type");
    if ("text".equals(type) || "reasoning".equals(type)) {
      return new ChatPart(
          messageId + ":" + type + ":" + ordinal, type, text(content, "text"), null, null);
    }
    if (!"tool".equals(type)) {
      return null;
    }
    var name = text(content, "name");
    var state = content.path("state");
    var status = text(state, "status");
    if ("streaming".equals(status)) {
      status = "pending";
    }
    var input = state.path("input").isObject() ? state.path("input") : null;
    var finished = "completed".equals(status) || "error".equals(status);
    var output =
        finished
            ? output(state.path("content"), "error".equals(status) ? state.path("error") : null)
            : null;
    return new ChatPart(text(content, "id"), "tool", name, name, status, input, output);
  }

  /** A tool part this stream has not seen start, as when it was opened mid-call. */
  private static ChatPart tool(ChatPart known, String id) {
    return known != null ? known : new ChatPart(id, "tool", null, null, "running");
  }

  /**
   * The text a tool returned, then the error it failed with, if any; files it returned are left
   * out. Cut to {@link ChatPart#MAX_OUTPUT} characters, keeping the end, where errors usually are.
   */
  private static String output(JsonNode content, JsonNode error) {
    var output = new StringBuilder();
    for (var item : content) {
      var text = text(item, "text");
      if ("text".equals(text(item, "type")) && text != null) {
        output.append(output.isEmpty() ? "" : "\n").append(text);
      }
    }
    if (error != null && text(error, "message") != null) {
      output.append(output.isEmpty() ? "" : "\n").append(text(error, "message"));
    }
    if (output.length() > ChatPart.MAX_OUTPUT) {
      return "…" + output.substring(output.length() - ChatPart.MAX_OUTPUT + 1);
    }
    return output.toString();
  }

  private static String partId(String type, JsonNode data) {
    return text(data, "assistantMessageID") + ":" + type + ":" + data.path("ordinal").asInt();
  }

  private JsonNode call(HarnessEndpoint endpoint, String method, String path, Object body) {
    var publisher =
        body == null
            ? BodyPublishers.noBody()
            : BodyPublishers.ofString(json.writeValueAsString(body));
    var response =
        check(
            send(
                request(endpoint, path)
                    .header("Content-Type", "application/json")
                    .method(method, publisher)));
    return response.body() == null || response.body().isBlank()
        ? json.nullNode()
        : json.readTree(response.body());
  }

  private HttpRequest.Builder request(HarnessEndpoint endpoint, String path) {
    var credentials = USER + ":" + endpoint.password();
    return HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
        .timeout(REQUEST_TIMEOUT)
        .header(
            "Authorization",
            "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<String> send(HttpRequest.Builder request) {
    try {
      return http.send(request.build(), BodyHandlers.ofString());
    } catch (IOException e) {
      throw new HarnessUnavailableException("the workspace's OpenCode service is not answering", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HarnessUnavailableException("interrupted calling OpenCode", e);
    }
  }

  private static HttpResponse<String> check(HttpResponse<String> response) {
    if (response.statusCode() / 100 != 2) {
      throw new HarnessUnavailableException("OpenCode V2 answered " + response.statusCode());
    }
    return response;
  }

  private static String withLocation(String path, String directory) {
    return path + (path.contains("?") ? "&" : "?") + "location%5Bdirectory%5D=" + encode(directory);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static URI websocket(URI base, String path) {
    var scheme = "https".equals(base.getScheme()) ? "wss" : "ws";
    return URI.create(scheme + "://" + base.getAuthority() + path);
  }

  private static Instant instant(Number millis) {
    return Instant.ofEpochMilli(millis.longValue());
  }

  private static Long longNumber(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asLong();
  }

  private static Double number(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asDouble();
  }

  private static String nullableText(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static String text(JsonNode node, String field) {
    return nullableText(node, field);
  }

  private static String error(JsonNode error) {
    var message = text(error, "message");
    if (message == null) {
      message = text(error.path("data"), "message");
    }
    return message == null ? "The reply failed" : message;
  }

  private static final class TerminalListener implements WebSocket.Listener {
    private final OutputStream output;
    private final AtomicBoolean closed;
    private final Runnable onExit;

    private TerminalListener(OutputStream output, AtomicBoolean closed, Runnable onExit) {
      this.output = output;
      this.closed = closed;
      this.onExit = onExit;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      write(data.toString().getBytes(StandardCharsets.UTF_8));
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      var bytes = new byte[data.remaining()];
      data.get(bytes);
      if (bytes.length == 0 || bytes[0] != 0) {
        write(bytes);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      exited();
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      exited();
    }

    private void write(byte[] bytes) {
      try {
        synchronized (output) {
          output.write(bytes);
          output.flush();
        }
      } catch (IOException e) {
        throw new HarnessUnavailableException("could not relay terminal output", e);
      }
    }

    private void exited() {
      if (closed.compareAndSet(false, true)) {
        onExit.run();
      }
    }
  }
}
