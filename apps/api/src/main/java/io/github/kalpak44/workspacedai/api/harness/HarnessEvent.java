package io.github.kalpak44.workspacedai.api.harness;

/**
 * What a session's stream reports, already narrowed to that one session. Each carries the
 * server-sent event name it is sent under.
 */
public sealed interface HarnessEvent {

  String name();

  /** A message started, or its role became known. */
  record Message(String id, String role) implements HarnessEvent {
    public String name() {
      return "message";
    }
  }

  /** A part's whole current state; it replaces whatever the client holds for that id. */
  record Part(String messageId, ChatPart part) implements HarnessEvent {
    public String name() {
      return "part";
    }
  }

  /** Text appended to a part, between the snapshots. */
  record Delta(String messageId, String partId, String delta) implements HarnessEvent {
    public String name() {
      return "delta";
    }
  }

  /** Whether the agent is working on a reply. */
  record Status(boolean busy) implements HarnessEvent {
    public String name() {
      return "status";
    }
  }

  /** The harness named the session after its first exchange. */
  record Title(String title) implements HarnessEvent {
    public String name() {
      return "title";
    }
  }

  /** The reply failed, e.g. the provider refused the key or ran out of credit. */
  record Error(String message) implements HarnessEvent {
    public String name() {
      return "error";
    }
  }

  /** Durable state changed and the browser should reconcile with message history. */
  record Refresh() implements HarnessEvent {
    public String name() {
      return "refresh";
    }
  }

  /** Nothing happened; sent so a proxy never sees the stream as idle. */
  record Heartbeat() implements HarnessEvent {
    public String name() {
      return "heartbeat";
    }
  }
}
