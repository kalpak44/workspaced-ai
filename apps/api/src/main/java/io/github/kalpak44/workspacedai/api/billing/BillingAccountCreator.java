package io.github.kalpak44.workspacedai.api.billing;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates an account in a transaction of its own, so losing the race to create it leaves the
 * caller's transaction usable.
 */
@Component
class BillingAccountCreator {

  private final BillingAccountRepository accounts;

  BillingAccountCreator(BillingAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void createIfAbsent(UUID user) {
    if (accounts.existsById(user)) {
      return;
    }
    try {
      accounts.saveAndFlush(new BillingAccountEntity(user));
    } catch (DataIntegrityViolationException _) {
      // Created meanwhile by another change.
    }
  }
}
