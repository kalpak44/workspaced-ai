package io.github.kalpak44.workspacedai.agenthub.tunnel;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.JsonNode;

/**
 * Every VM's open tunnel workers, in memory: the app runs as a single replica, so the socket a call
 * needs is always on this instance.
 */
@Component
public class WorkerRegistry {

  private static final class Pool {
    final Deque<Worker> idle = new ArrayDeque<>();
    int open;
  }

  // One lock for every VM: a handful of VMs and a few calls a second never contend on it.
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition added = lock.newCondition();
  private final Map<UUID, Pool> pools = new HashMap<>();
  private final Duration wait;
  private final int maxWorkers;

  public WorkerRegistry(AgentHubProperties properties) {
    this.wait = properties.workerWait();
    this.maxWorkers = properties.maxWorkers();
  }

  /**
   * @return false when the VM already holds as many as it may, leaving the worker unregistered
   */
  boolean add(Worker worker) {
    lock.lock();
    try {
      var pool = pools.computeIfAbsent(worker.vmId(), _ -> new Pool());
      if (pool.open >= maxWorkers) {
        return false;
      }
      pool.open++;
      pool.idle.addLast(worker);
      added.signalAll();
      return true;
    } finally {
      lock.unlock();
    }
  }

  /** Forgets a worker {@link #add} accepted, once its socket has closed, idle or busy. */
  void remove(Worker worker) {
    lock.lock();
    try {
      var pool = pools.get(worker.vmId());
      if (pool == null) {
        return;
      }
      pool.idle.remove(worker);
      if (--pool.open <= 0) {
        pools.remove(worker.vmId());
      }
    } finally {
      lock.unlock();
    }
  }

  /** How many of the VM's workers are waiting for an exchange. */
  public int idle(UUID vmId) {
    lock.lock();
    try {
      var pool = pools.get(vmId);
      return pool == null ? 0 : pool.idle.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Takes one of the VM's idle workers and starts an exchange on it, waiting for one to come free
   * or connect. A worker whose socket died without closing fails the head, and the next is tried.
   *
   * @throws TunnelException 503 when none does in time
   */
  Worker start(UUID vmId, WorkerListener listener, JsonNode head) {
    var deadline = System.nanoTime() + wait.toNanos();
    while (true) {
      var worker = take(vmId, deadline);
      try {
        worker.start(listener, head);
        return worker;
      } catch (IOException e) {
        worker.close(CloseStatus.SERVER_ERROR);
      }
    }
  }

  // The most recent first: the longer a socket has sat idle, the likelier it is to be dead.
  private Worker take(UUID vmId, long deadline) {
    lock.lock();
    try {
      while (true) {
        var pool = pools.get(vmId);
        var worker = pool == null ? null : pool.idle.pollLast();
        if (worker != null) {
          return worker;
        }
        var left = deadline - System.nanoTime();
        if (left <= 0) {
          throw TunnelException.notConnected();
        }
        added.awaitNanos(left);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw TunnelException.notConnected();
    } finally {
      lock.unlock();
    }
  }
}
