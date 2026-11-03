package io.github.kalpak44.workspacedai.api.session;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** One agent run: the stream a client watches and the prompts still waiting on it. */
public final class Session {

  private final String id;
  private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
  private SseEmitter emitter;

  Session(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  /** One watcher per run: a second stream would start a second agent on the same session. */
  synchronized boolean attach(SseEmitter emitter) {
    if (this.emitter != null) {
      return false;
    }
    this.emitter = emitter;
    return true;
  }

  /**
   * Sends one named event. Synchronized because the agent thread and the request threads both
   * write, and an SseEmitter interleaves concurrent sends into a corrupt stream.
   */
  synchronized void send(String event, Object data) throws IOException {
    if (emitter == null) {
      throw new IOException("no client is watching session " + id);
    }
    emitter.send(SseEmitter.event().name(event).data(data));
  }

  synchronized void complete() {
    if (emitter != null) {
      emitter.complete();
    }
  }

  /** Registers a prompt the agent blocks on until a client resolves it. */
  CompletableFuture<String> await(String promptId) {
    var reply = new CompletableFuture<String>();
    pending.put(promptId, reply);
    return reply;
  }

  /** Returns false when no prompt with that id is waiting, so the caller can answer 404. */
  boolean resolve(String promptId, String value) {
    var reply = pending.remove(promptId);
    return reply != null && reply.complete(value);
  }
}
