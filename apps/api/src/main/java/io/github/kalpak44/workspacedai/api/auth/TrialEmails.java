package io.github.kalpak44.workspacedai.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Which account for an inbox had its free trial with. Every alias Auth0 verifies separately
 * delivers to the same inbox, so without this one person gets a free demo VM and a funded AI key
 * per alias.
 */
@Component
class TrialEmails {

  private static final String ALGORITHM = "HmacSHA256";
  private static final Set<String> GMAIL = Set.of("gmail.com", "googlemail.com");

  private final TrialEmailRepository emails;
  private final SecretKeySpec key;

  TrialEmails(TrialEmailRepository emails, AuthProperties properties) {
    this.emails = emails;
    // A label of its own, so the hash can never pass as anything signed with the login key.
    this.key =
        new SecretKeySpec(
            ("trial-email:" + properties.jwtSecret()).getBytes(StandardCharsets.UTF_8), ALGORITHM);
  }

  /**
   * Records the address against the account unless another account had it first. Runs in the
   * caller's transaction.
   *
   * @return whether this account is the one the address had its trial with
   */
  boolean claim(UUID user, String email) {
    var hash = hash(canonical(email));
    if (emails.insertIfAbsent(hash, user) == 1) {
      return true;
    }
    return emails.findById(hash).map(TrialEmailEntity::getUserId).filter(user::equals).isPresent();
  }

  /** The address every alias of it delivers to: no +tag, and for Gmail no dots. */
  static String canonical(String email) {
    var address = email.strip().toLowerCase(Locale.ROOT);
    var at = address.lastIndexOf('@');
    if (at < 0) {
      return address;
    }
    var local = address.substring(0, at);
    var domain = address.substring(at + 1);
    var plus = local.indexOf('+');
    if (plus >= 0) {
      local = local.substring(0, plus);
    }
    if (GMAIL.contains(domain)) {
      local = local.replace(".", "");
      domain = "gmail.com";
    }
    return local + "@" + domain;
  }

  private String hash(String canonical) {
    try {
      var mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }
}
