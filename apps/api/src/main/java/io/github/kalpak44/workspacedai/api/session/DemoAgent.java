package io.github.kalpak44.workspacedai.api.session;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A scripted stand-in for a real agent. It emits every event type a client has to handle, in the
 * order a real run would, and blocks on the same prompts.
 */
@Component
public class DemoAgent {

  static final String APPROVE = "approve";

  private static final Logger log = LoggerFactory.getLogger(DemoAgent.class);

  private final DemoProperties properties;
  private final SessionRegistry registry;

  public DemoAgent(DemoProperties properties, SessionRegistry registry) {
    this.properties = properties;
    this.registry = registry;
  }

  /** Runs on a virtual thread, because the script spends nearly all its time waiting. */
  public void start(Session session) {
    Thread.ofVirtual().name("demo-agent-" + session.id()).start(() -> run(session));
  }

  void run(Session session) {
    try {
      script(session);
      session.complete();
    } catch (IOException e) {
      // The client went away; there is nobody left to stream to.
      log.debug("Session {} stream closed: {}", session.id(), e.getMessage());
    } catch (TimeoutException e) {
      log.info("Session {} abandoned waiting on a prompt", session.id());
      session.complete();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      log.warn("Session {} failed", session.id(), e.getCause());
    } finally {
      registry.remove(session.id());
    }
  }

  private void script(Session session)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    session.send("status", Map.of("state", "running"));

    stream(session, "I'll look at the workspace before changing anything.");
    run(session, "ls -la", List.of("total 8", "drwxr-xr-x  apps", "drwxr-xr-x  docs"), 0);

    var questionId = UUID.randomUUID().toString();
    var reply = session.await(questionId);
    session.send(
        "question",
        Map.of(
            "id",
            questionId,
            "text",
            "Which branch should I work on?",
            "options",
            List.of("main", "develop")));
    var answer = waitFor(reply);
    session.send("answer", Map.of("questionId", questionId, "answer", answer));

    stream(session, "Working on " + answer + ". The build directory needs clearing first.");
    var commandId = UUID.randomUUID().toString();
    var command = "rm -rf build/";
    var decision = session.await(commandId);
    session.send("approval", Map.of("id", commandId, "command", command));
    var verdict = waitFor(decision);
    session.send("decision", Map.of("commandId", commandId, "decision", verdict));

    if (APPROVE.equals(verdict)) {
      run(session, commandId, command, List.of("removed build/"), 0);
    } else {
      stream(session, "Skipping it; the build directory stays as it is.");
    }

    session.send("status", Map.of("state", "completed"));
  }

  private void stream(Session session, String text) throws IOException, InterruptedException {
    for (var token : text.split("(?<= )")) {
      session.send("token", Map.of("text", token));
      pause();
    }
    session.send("message", Map.of("text", text));
  }

  private void run(Session session, String command, List<String> lines, int code)
      throws IOException, InterruptedException {
    run(session, UUID.randomUUID().toString(), command, lines, code);
  }

  private void run(Session session, String id, String command, List<String> lines, int code)
      throws IOException, InterruptedException {
    session.send("command", Map.of("id", id, "command", command));
    for (var line : lines) {
      session.send("output", Map.of("commandId", id, "stream", "stdout", "text", line));
      pause();
    }
    session.send("exit", Map.of("commandId", id, "code", code));
  }

  private String waitFor(CompletableFuture<String> reply)
      throws InterruptedException, ExecutionException, TimeoutException {
    return reply.get(properties.promptTimeout().toMillis(), TimeUnit.MILLISECONDS);
  }

  private void pause() throws InterruptedException {
    Duration delay = properties.tokenDelay();
    if (!delay.isZero()) {
      Thread.sleep(delay);
    }
  }
}
