package io.github.kalpak44.workspacedai.api;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * What one browser's event stream has still to be sent, sent from a thread of its own. Whoever
 * produces events only queues them, so a browser that stops reading, whose socket then blocks,
 * holds up its own stream and nobody else's.
 *
 * <p>The queue is bounded: a browser that falls this far behind is disconnected rather than
 * buffered for without end. Its EventSource reconnects by itself, and the page reloads what it
 * missed.
 */
public final class SseOutbox {

  static final int CAPACITY = 1024;

  // Queued after the last event to complete the stream once everything before it is sent.
  private static final SseEmitter.SseEventBuilder END = SseEmitter.event();

  private final SseEmitter emitter;
  private final BlockingQueue<SseEmitter.SseEventBuilder> queue =
      new ArrayBlockingQueue<>(CAPACITY);
  private final Thread sender;
  private volatile boolean closed;

  public SseOutbox(SseEmitter emitter, String name) {
    this.emitter = emitter;
    this.sender = Thread.ofVirtual().name(name).start(this::drain);
  }

  /** Never blocks. A full queue disconnects the browser. */
  public void offer(SseEmitter.SseEventBuilder event) {
    if (!closed && !queue.offer(event)) {
      fail(new IllegalStateException("the event stream fell too far behind"));
    }
  }

  /** Completes the stream once what is already queued has been sent. */
  public void complete() {
    if (!closed && !queue.offer(END)) {
      closed = true;
      sender.interrupt();
      emitter.complete();
    }
  }

  /** Stops sending without completing the stream, for one that has already ended. */
  public void close() {
    closed = true;
    sender.interrupt();
  }

  private void drain() {
    try {
      while (!closed) {
        var event = queue.take();
        if (event == END) {
          closed = true;
          emitter.complete();
          return;
        }
        emitter.send(event);
      }
    } catch (InterruptedException _) {
      // Closed: nothing more is sent.
    } catch (IOException | IllegalStateException e) {
      fail(e);
    }
  }

  private void fail(Exception cause) {
    closed = true;
    sender.interrupt();
    emitter.completeWithError(cause);
  }
}
