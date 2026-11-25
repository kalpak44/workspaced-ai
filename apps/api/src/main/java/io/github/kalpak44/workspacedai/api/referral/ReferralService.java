package io.github.kalpak44.workspacedai.api.referral;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

/** A user's invitation link and the friends it brought. */
@Service
public class ReferralService {

  // No 0/o, 1/l or i, so a code read aloud or typed from a screen comes out right.
  private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
  private static final int CODE_LENGTH = 8;
  private static final int ATTEMPTS = 5;
  private static final long MICROS_PER_CENT = 10_000;

  private final ReferralProperties properties;
  private final ReferralCodeRepository codes;
  private final ReferralRepository referrals;
  private final SecureRandom random = new SecureRandom();

  public ReferralService(
      ReferralProperties properties, ReferralCodeRepository codes, ReferralRepository referrals) {
    this.properties = properties;
    this.codes = codes;
    this.referrals = referrals;
  }

  /** Makes the user's code the first time; an admin gets none. */
  @Transactional
  public Referrals overview(UUID user, boolean admin) {
    var eligible = !admin;
    var code = eligible && properties.enabled() ? code(user) : null;
    var now = Instant.now();
    var friends =
        referrals.findByReferrerIdOrderBySignedUpAtDesc(user).stream()
            .map(referral -> friend(referral, now))
            .toList();
    return new Referrals(
        properties.enabled(),
        eligible,
        code,
        code == null
            ? null
            : UriComponentsBuilder.fromUri(properties.link())
                .queryParam("ref", code)
                .build()
                .toUri(),
        terms(properties.referee()),
        terms(properties.referrer()),
        friends.size(),
        (int) friends.stream().filter(friend -> friend.firstPaidAt() != null).count(),
        friends.stream().mapToLong(Referrals.Friend::earnedCents).sum(),
        friends);
  }

  private String code(UUID user) {
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      var existing = codes.findById(user);
      if (existing.isPresent()) {
        return existing.get().getCode();
      }
      // A taken code inserts nothing, and the loop draws another.
      codes.insertIfAbsent(user, draw());
    }
    return codes
        .findById(user)
        .map(ReferralCodeEntity::getCode)
        .orElseThrow(() -> new IllegalStateException("no free referral code for " + user));
  }

  private String draw() {
    var code = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }

  private Referrals.Friend friend(ReferralEntity referral, Instant now) {
    var until = referral.getSignedUpAt().plus(properties.referrer().period());
    var capped = referral.getRewardMicros() >= ReferralRewards.capMicros(properties.referrer());
    Referrals.Status status;
    if (capped || now.isAfter(until)) {
      status = Referrals.Status.FINISHED;
    } else if (referral.getFirstPaidAt() != null) {
      status = Referrals.Status.EARNING;
    } else {
      status = Referrals.Status.SIGNED_UP;
    }
    return new Referrals.Friend(
        referral.getSignedUpAt(),
        referral.getFirstPaidAt(),
        status,
        referral.getRewardMicros() / MICROS_PER_CENT,
        until);
  }

  private static Referrals.Terms terms(ReferralProperties.Side side) {
    return new Referrals.Terms(
        side.percent(), ReferralRewards.capMicros(side) / MICROS_PER_CENT, side.period().toDays());
  }
}
