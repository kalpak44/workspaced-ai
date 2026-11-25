package io.github.kalpak44.workspacedai.api.referral;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.billing.TopUpRewards;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * On an invited user's paid top-up: a bonus for them and a reward for whoever invited them, each a
 * share of the top-up while its period runs and until its cap. Only paid top-ups ask, so credit
 * never earns credit.
 */
@Component
public class ReferralRewards implements TopUpRewards {

  static final String BONUS = "REFERRAL_BONUS";
  static final String REWARD = "REFERRAL_REWARD";

  private static final long MICROS_PER_CENT = 10_000;
  private static final BigDecimal MICROS_PER_EURO = BigDecimal.valueOf(1_000_000);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final ReferralProperties properties;
  private final ReferralRepository referrals;
  private final UserRepository users;

  public ReferralRewards(
      ReferralProperties properties, ReferralRepository referrals, UserRepository users) {
    this.properties = properties;
    this.referrals = referrals;
    this.users = users;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<Reward> paid(UUID user, UUID topUp, long micros) {
    if (!properties.enabled()) {
      return List.of();
    }
    var referral = referrals.findLockedByRefereeId(user).orElse(null);
    if (referral == null) {
      return List.of();
    }
    var now = Instant.now();
    referral.paid(now);
    // An admin pays cost price, so a top-up of theirs leaves no margin to share.
    if (admin(user)) {
      return List.of();
    }
    var rewards = new ArrayList<Reward>();
    var bonus =
        share(
            micros, properties.referee(), referral.getBonusMicros(), referral.getSignedUpAt(), now);
    if (bonus > 0) {
      referral.addBonus(bonus);
      rewards.add(new Reward(user, bonus, BONUS, Notifications::referralBonus));
    }
    if (!admin(referral.getReferrerId())) {
      var reward =
          share(
              micros,
              properties.referrer(),
              referral.getRewardMicros(),
              referral.getSignedUpAt(),
              now);
      if (reward > 0) {
        referral.addReward(reward);
        rewards.add(
            new Reward(referral.getReferrerId(), reward, REWARD, Notifications::referralReward));
      }
    }
    return rewards;
  }

  /** The side's share of a top-up, in whole cents, or what is left of its cap if that is less. */
  static long share(
      long micros, ReferralProperties.Side side, long already, Instant signedUp, Instant now) {
    if (now.isAfter(signedUp.plus(side.period()))) {
      return 0;
    }
    var share =
        BigDecimal.valueOf(micros)
                .multiply(side.percent())
                .divide(
                    HUNDRED.multiply(BigDecimal.valueOf(MICROS_PER_CENT)), 0, RoundingMode.FLOOR)
                .longValueExact()
            * MICROS_PER_CENT;
    var left = capMicros(side) - already;
    return Math.max(0, Math.min(share, left));
  }

  static long capMicros(ReferralProperties.Side side) {
    return side.cap().multiply(MICROS_PER_EURO).longValueExact();
  }

  private boolean admin(UUID user) {
    return users.findById(user).map(u -> u.getRole() == Role.ADMIN).orElse(true);
  }
}
