package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.notification.NotificationMessage;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Credit granted because a top-up was paid, such as a referral's. Asked once per top-up, in the
 * transaction that credits it, so a reward is posted exactly when its top-up is.
 */
public interface TopUpRewards {

  /**
   * @param kind the ledger kind, at most 20 characters; with the top-up it makes the entry unique
   * @param message what the receiver is told, given the amount as shown to users
   */
  record Reward(
      UUID user, long micros, String kind, Function<String, NotificationMessage> message) {}

  /**
   * @param user who paid
   * @param micros what the top-up added to their balance, before VAT
   */
  List<Reward> paid(UUID user, UUID topUp, long micros);

  /**
   * A reward of this program was taken back because its top-up was refunded or disputed, in the
   * same transaction, so whatever the program counts towards its caps can be lowered with it.
   *
   * @param payer who paid the top-up
   * @param kind the kind the reward was posted as
   * @param micros what was taken back, a positive amount
   */
  default void reversed(UUID payer, String kind, long micros) {}
}
