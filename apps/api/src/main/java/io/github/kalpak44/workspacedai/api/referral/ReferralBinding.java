package io.github.kalpak44.workspacedai.api.referral;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserCreated;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Binds a new account to the user whose code its sign-up link carried, once, when the account is
 * made. An unknown code binds nothing, and admins take part on neither side, since they pay cost
 * price and so bring no margin to share.
 */
@Component
public class ReferralBinding {

  private final ReferralProperties properties;
  private final ReferralCodeRepository codes;
  private final ReferralRepository referrals;
  private final UserRepository users;

  public ReferralBinding(
      ReferralProperties properties,
      ReferralCodeRepository codes,
      ReferralRepository referrals,
      UserRepository users) {
    this.properties = properties;
    this.codes = codes;
    this.referrals = referrals;
    this.users = users;
  }

  @TransactionalEventListener
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void created(UserCreated event) {
    if (!properties.enabled() || event.referral() == null || event.role() == Role.ADMIN) {
      return;
    }
    codes
        .findByCode(event.referral())
        .map(ReferralCodeEntity::getUserId)
        .filter(referrer -> !referrer.equals(event.userId()))
        .filter(
            referrer -> users.findById(referrer).filter(u -> u.getRole() != Role.ADMIN).isPresent())
        .ifPresent(referrer -> referrals.save(new ReferralEntity(event.userId(), referrer)));
  }
}
