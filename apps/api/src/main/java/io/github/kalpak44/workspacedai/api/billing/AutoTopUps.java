package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Charges the saved card of each user who turned automatic top-ups on, once their balance is below
 * the threshold they chose. {@link UsageCharger} runs it after charging hours, so a paused
 * workspace the top-up pays for starts again in the same run. A charge that cannot reach Stripe
 * stays pending and is tried again, with the same keys, on the next run; a declined card turns
 * automatic top-ups off, so it is not tried again and again. An admin is never charged.
 */
@Component
class AutoTopUps {

  private static final Logger log = LoggerFactory.getLogger(AutoTopUps.class);

  private final BillingAccountRepository accounts;
  private final TopUpRepository topUps;
  private final Ledger ledger;
  private final TransactionTemplate transactions;
  private final Stripe stripe;
  private final BillingService billing;
  private final NotificationService notifications;
  private final BillingProperties properties;

  AutoTopUps(
      BillingAccountRepository accounts,
      TopUpRepository topUps,
      Ledger ledger,
      TransactionTemplate transactions,
      Stripe stripe,
      BillingService billing,
      NotificationService notifications,
      BillingProperties properties) {
    this.accounts = accounts;
    this.topUps = topUps;
    this.ledger = ledger;
    this.transactions = transactions;
    this.stripe = stripe;
    this.billing = billing;
    this.notifications = notifications;
    this.properties = properties;
  }

  void run() {
    for (var account : accounts.findAllByAutoTopUpEnabledTrue()) {
      try {
        topUp(account.getUserId());
      } catch (RuntimeException e) {
        // The others are still topped up; this one is tried again on the next run.
        log.warn("could not top up the balance of user {} automatically", account.getUserId(), e);
      }
    }
  }

  /** Charges the user's card if their balance is low, or finishes a charge left pending. */
  void topUp(UUID user) {
    if (billing.isFree(user)) {
      return;
    }
    var topUp = pendingOrNew(user);
    if (topUp != null) {
      charge(topUp);
    }
  }

  // The account stays locked while the top-up is stored, so two runs never start two at once.
  private TopUpEntity pendingOrNew(UUID user) {
    return transactions.execute(
        _ -> {
          var account = ledger.account(user);
          var pending =
              topUps.findFirstByUserIdAndAutomaticTrueAndStatus(user, TopUpStatus.PENDING);
          if (pending.isPresent()) {
            return pending.get();
          }
          var below = account.getAutoTopUpBelowCents();
          var amount = account.getAutoTopUpAmountCents();
          if (!account.isAutoTopUpEnabled()
              || account.getPaymentMethodId() == null
              || below == null
              || amount == null
              || account.getBalanceMicros() >= below * Ledger.MICROS_PER_CENT) {
            return null;
          }
          return topUps.save(TopUpEntity.automatic(UUID.randomUUID(), user, amount));
        });
  }

  private void charge(TopUpEntity topUp) {
    var user = topUp.getUserId();
    var card = accounts.findById(user).map(BillingAccountEntity::getPaymentMethodId).orElse(null);
    if (card == null) {
      // The card was removed while the charge was pending; there is nothing left to charge.
      end(topUp, TopUpStatus.EXPIRED, null);
      return;
    }
    Stripe.ChargedInvoice invoice;
    try {
      invoice =
          stripe.chargeCard(billing.customer(user), card, topUp.getId(), topUp.getAmountCents());
    } catch (CardDeclinedException e) {
      end(topUp, TopUpStatus.FAILED, e.getMessage());
      return;
    }
    var amount = topUp.getAmountCents();
    if (!invoice.paid()) {
      end(topUp, TopUpStatus.FAILED, "The payment did not go through.");
      return;
    }
    if (invoice.subtotal() != amount
        || invoice.total() != amount + billing.vatCents(amount)
        || !properties.currency().equalsIgnoreCase(invoice.currency())) {
      log.error(
          "automatic top-up {} was for {} cents but Stripe charged {} of {} {}; not credited",
          topUp.getId(),
          amount,
          invoice.subtotal(),
          invoice.total(),
          invoice.currency());
      end(topUp, TopUpStatus.FAILED, "The charge did not match the top-up.");
      return;
    }
    if (billing.credit(topUp.getId(), invoice.id(), invoice.number())) {
      log.info("credited automatic top-up {} of {} cents", topUp.getId(), amount);
    }
  }

  // A failure turns automatic top-ups off and says why, by mail as well, since a balance no longer
  // topped up is soon a paused workspace; an expired one is dropped quietly.
  private void end(TopUpEntity topUp, TopUpStatus status, String reason) {
    transactions.executeWithoutResult(
        _ ->
            topUps
                .findLockedById(topUp.getId())
                .filter(locked -> locked.getStatus() == TopUpStatus.PENDING)
                .ifPresent(
                    locked -> {
                      locked.setStatus(status);
                      if (status != TopUpStatus.FAILED) {
                        return;
                      }
                      ledger.account(locked.getUserId()).disableAutoTopUp();
                      notifications.sendAndMail(
                          locked.getUserId(),
                          Notifications.autoTopUpFailed(
                              billing.amount(locked.getAmountCents() * Ledger.MICROS_PER_CENT),
                              reason));
                    }));
  }
}
