package io.github.kalpak44.workspacedai.api;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * A named lock every API instance respects, for a change that has to be read, modified and written
 * back through a harness. It is a lease in the database's ShedLock table, not a lock held on a
 * connection, so the harness calls it guards hold no database connection; an instance that dies
 * holding one frees it once the lease runs out.
 */
@Component
public class ClusterLocks {

  private static final Duration LEASE = Duration.ofMinutes(2);
  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

  private final LockProvider provider;
  private final Duration wait;

  public ClusterLocks(
      LockProvider provider, @Value("${workspaced.locks.wait:PT30S}") Duration wait) {
    this.provider = provider;
    this.wait = wait;
  }

  /** Waits its turn for the lock, then runs the action; a 409 if the turn does not come in time. */
  public <T> T call(String name, Supplier<T> action) {
    var lock = acquire(name);
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  public void run(String name, Runnable action) {
    call(
        name,
        () -> {
          action.run();
          return null;
        });
  }

  private SimpleLock acquire(String name) {
    var deadline = System.nanoTime() + wait.toNanos();
    while (true) {
      var lock = provider.lock(new LockConfiguration(Instant.now(), name, LEASE, Duration.ZERO));
      if (lock.isPresent()) {
        return lock.get();
      }
      if (System.nanoTime() - deadline > 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "another change to the same settings is still running; try again");
      }
      LockSupport.parkNanos(POLL_NANOS);
    }
  }
}
