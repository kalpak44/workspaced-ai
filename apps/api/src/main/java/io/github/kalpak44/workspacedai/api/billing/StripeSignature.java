package io.github.kalpak44.workspacedai.api.billing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checks the Stripe-Signature header the way Stripe's libraries do: an HMAC-SHA256 of
 * "timestamp.body" under the endpoint's signing secret, within a tolerance so a captured event
 * cannot be replayed later.
 */
final class StripeSignature {

  static final Duration TOLERANCE = Duration.ofMinutes(5);

  private StripeSignature() {}

  static boolean valid(String header, byte[] payload, String secret, Instant now) {
    if (header == null || secret == null || secret.isBlank()) {
      return false;
    }
    long timestamp = -1;
    var signatures = new ArrayList<String>();
    for (var part : header.split(",")) {
      var pair = part.strip().split("=", 2);
      if (pair.length != 2) {
        continue;
      }
      if (pair[0].equals("t")) {
        try {
          timestamp = Long.parseLong(pair[1]);
        } catch (NumberFormatException _) {
          return false;
        }
      } else if (pair[0].equals("v1")) {
        signatures.add(pair[1]);
      }
    }
    if (timestamp < 0
        || Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(TOLERANCE) > 0) {
      return false;
    }
    var expected = sign(timestamp, payload, secret);
    return signatures.stream()
        .anyMatch(s -> MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.US_ASCII)));
  }

  static byte[] sign(long timestamp, byte[] payload, String secret) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
      var digest = mac.doFinal(payload);
      return HexFormat.of().formatHex(digest).getBytes(StandardCharsets.US_ASCII);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }
}
