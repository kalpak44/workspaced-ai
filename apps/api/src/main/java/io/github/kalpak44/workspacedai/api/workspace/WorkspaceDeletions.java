package io.github.kalpak44.workspacedai.api.workspace;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Carries a workspace's deletion to every API instance, so each tells its own browsers. A browser's
 * event stream is held by whichever instance it connected to, which is rarely the one that handled
 * the delete.
 *
 * <p>PostgreSQL's NOTIFY does the carrying: it is sent with the delete's transaction and only if
 * that commits, and every instance LISTENs on a connection of its own, outside the pool, which a
 * notification arrives on. A notification sent while an instance is reconnecting is lost to it; its
 * browsers then see the workspace fail and drop it on their next reload.
 */
@Component
class WorkspaceDeletions implements SmartLifecycle {

  static final String CHANNEL = "workspace_deleted";

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceDeletions.class);
  private static final int POLL_MILLIS = 5_000;
  private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(5);

  private final JdbcTemplate jdbc;
  private final JdbcConnectionDetails database;
  private final WorkspaceEvents events;

  private volatile boolean running;
  private volatile Connection connection;
  private Thread receiver;

  WorkspaceDeletions(JdbcTemplate jdbc, JdbcConnectionDetails database, WorkspaceEvents events) {
    this.jdbc = jdbc;
    this.database = database;
    this.events = events;
  }

  /** Part of the delete's transaction, so a delete rolled back is never announced. */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  void announce(WorkspaceDeleted deleted) {
    jdbc.query(
        "select pg_notify(?, ?)",
        (RowCallbackHandler) _ -> {},
        CHANNEL,
        deleted.id() + ":" + deleted.ownerId());
  }

  /**
   * Listening before this returns, so a deletion made as soon as the application is up is not
   * missed. A database not reachable yet is retried by the receiver.
   */
  @Override
  public synchronized void start() {
    running = true;
    try {
      connection = listen();
    } catch (SQLException e) {
      LOG.warn("could not listen for workspace deletions yet; retrying", e);
    }
    receiver = Thread.ofPlatform().daemon().name("workspace-deletions").start(this::receive);
  }

  @Override
  public synchronized void stop() {
    running = false;
    close();
    if (receiver != null) {
      receiver.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void receive() {
    while (running) {
      try {
        if (connection == null) {
          connection = listen();
          if (!running) {
            close();
            return;
          }
        }
        var notifications = connection.unwrap(PGConnection.class).getNotifications(POLL_MILLIS);
        if (notifications != null) {
          for (var notification : notifications) {
            deliver(notification.getParameter());
          }
        }
      } catch (SQLException e) {
        if (!running) {
          return;
        }
        LOG.warn("lost the workspace deletion channel; reconnecting", e);
        close();
        LockSupport.parkNanos(RETRY_NANOS);
      }
    }
  }

  private Connection listen() throws SQLException {
    var listening =
        DriverManager.getConnection(
            database.getJdbcUrl(), database.getUsername(), database.getPassword());
    try (var statement = listening.createStatement()) {
      statement.execute("LISTEN " + CHANNEL);
    } catch (SQLException e) {
      listening.close();
      throw e;
    }
    return listening;
  }

  private void deliver(String payload) {
    var parts = payload.split(":", 2);
    try {
      events.deleted(UUID.fromString(parts[0]), UUID.fromString(parts[1]));
    } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
      LOG.warn("ignored a malformed workspace deletion notice {}", payload, e);
    }
  }

  private void close() {
    var current = connection;
    connection = null;
    if (current != null) {
      try {
        current.close();
      } catch (SQLException _) {
        // Already unusable; a new one is opened if still running.
      }
    }
  }
}
