package io.github.kalpak44.workspacedai.api.compute;

import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Users who asked, while every regular plan was sold out, to be told when one is back. Each is
 * emailed once, then forgotten; it is mailed whatever their tips setting, since they asked for it.
 */
@Service
public class PlanWaiters {

  private final PlanWaiterRepository waiters;
  private final NotificationService notifications;

  public PlanWaiters(PlanWaiterRepository waiters, NotificationService notifications) {
    this.waiters = waiters;
    this.notifications = notifications;
  }

  @Transactional(readOnly = true)
  public boolean waiting(UUID user) {
    return waiters.existsById(user);
  }

  @Transactional
  public void add(UUID user) {
    waiters.insertIfAbsent(user);
  }

  @Transactional
  public void stop(UUID user) {
    waiters.deleteById(user);
  }

  /** Tells everyone waiting, in the caller's transaction, so each is told once. */
  @Transactional(propagation = Propagation.MANDATORY)
  void planIsBack() {
    for (var waiter : waiters.findAll()) {
      notifications.sendAndMail(waiter.getUserId(), Notifications.planBack());
      waiters.delete(waiter);
    }
  }
}
