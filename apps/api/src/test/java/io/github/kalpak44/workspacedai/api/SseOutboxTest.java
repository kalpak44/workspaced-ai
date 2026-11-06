package io.github.kalpak44.workspacedai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Timeout(10)
class SseOutboxTest {

  /** A browser's stream whose socket can be made to block, as one that stops reading does. */
  static final class Browser extends SseEmitter {
    final List<SseEventBuilder> received = new CopyOnWriteArrayList<>();
    final CountDownLatch reading = new CountDownLatch(1);
    final CompletableFuture<Throwable> ended = new CompletableFuture<>();
    volatile IOException failure;

    @Override
    public void send(SseEventBuilder event) throws IOException {
      try {
        reading.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
      if (failure != null) {
        throw failure;
      }
      received.add(event);
    }

    @Override
    public void complete() {
      ended.complete(null);
    }

    @Override
    public void completeWithError(Throwable cause) {
      ended.complete(cause);
    }
  }

  @Test
  void neverMakesTheProducerWaitAndKeepsTheOrder() throws Exception {
    var browser = new Browser();
    var outbox = new SseOutbox(browser, "test");
    var events = new ArrayList<SseEmitter.SseEventBuilder>();
    for (var i = 0; i < 100; i++) {
      events.add(SseEmitter.event().comment("e" + i));
    }
    var started = System.nanoTime();
    events.forEach(outbox::offer);
    // The browser is not reading, yet queueing a hundred events took no time.
    assertThat(System.nanoTime() - started).isLessThan(TimeUnit.MILLISECONDS.toNanos(500));
    assertThat(browser.received).isEmpty();

    browser.reading.countDown();
    outbox.complete();
    assertThat(browser.ended.get(5, TimeUnit.SECONDS)).isNull();
    assertThat(browser.received).containsExactlyElementsOf(events);
  }

  @Test
  void disconnectsABrowserThatFellTooFarBehind() throws Exception {
    var browser = new Browser();
    var outbox = new SseOutbox(browser, "test");
    for (var i = 0; i < SseOutbox.CAPACITY + 2; i++) {
      outbox.offer(SseEmitter.event().comment("e" + i));
    }
    assertThat(browser.ended.get(5, TimeUnit.SECONDS)).hasMessageContaining("too far behind");
  }

  @Test
  void endsTheStreamWhenSendingFails() throws Exception {
    var browser = new Browser();
    browser.failure = new IOException("broken pipe");
    browser.reading.countDown();
    var outbox = new SseOutbox(browser, "test");
    outbox.offer(SseEmitter.event().comment("e"));
    assertThat(browser.ended.get(5, TimeUnit.SECONDS)).hasMessage("broken pipe");
  }

  @Test
  void sendsNothingOnceClosed() throws Exception {
    var browser = new Browser();
    browser.reading.countDown();
    var outbox = new SseOutbox(browser, "test");
    outbox.close();
    outbox.offer(SseEmitter.event().comment("late"));
    outbox.complete();
    Thread.sleep(200);
    assertThat(browser.received).isEmpty();
    assertThat(browser.ended).isNotDone();
  }
}
