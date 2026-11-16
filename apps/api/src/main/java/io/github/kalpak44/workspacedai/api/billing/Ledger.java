package io.github.kalpak44.workspacedai.api.billing;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a balance changes, always together with the ledger entry recording why. It joins
 * the caller's transaction, which holds whatever row makes the change safe to repeat.
 */
@Component
class Ledger {

  static final long MICROS_PER_CENT = 10_000;
  static final String TOP_UP = "TOP_UP";
  static final String USAGE = "USAGE";

  private final BillingAccountRepository accounts;
  private final LedgerEntryRepository entries;
  private final BillingAccountCreator creator;

  Ledger(
      BillingAccountRepository accounts,
      LedgerEntryRepository entries,
      BillingAccountCreator creator) {
    this.accounts = accounts;
    this.entries = entries;
    this.creator = creator;
  }

  /**
   * Moves the balance by the amount once per kind and reference, so a retried credit or charge is
   * recorded once.
   *
   * @return whether this call moved it
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean post(UUID user, long micros, String kind, String reference) {
    if (entries.existsByKindAndReference(kind, reference)) {
      return false;
    }
    var account = account(user);
    entries.save(new LedgerEntryEntity(user, micros, kind, reference));
    account.add(micros);
    return true;
  }

  /** The user's account, locked to the end of the transaction and created on first use. */
  @Transactional(propagation = Propagation.MANDATORY)
  public BillingAccountEntity account(UUID user) {
    return accounts
        .findLockedByUserId(user)
        .orElseGet(
            () -> {
              creator.createIfAbsent(user);
              return accounts.findLockedByUserId(user).orElseThrow();
            });
  }
}
