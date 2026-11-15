package io.github.kalpak44.workspacedai.api.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StripeSignatureTest {

  private static final String SECRET = "whsec_test";
  private static final byte[] PAYLOAD = "{\"type\":\"ping\"}".getBytes(StandardCharsets.UTF_8);
  private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);

  private static String header(long timestamp, String secret) {
    return "t="
        + timestamp
        + ",v1="
        + new String(StripeSignature.sign(timestamp, PAYLOAD, secret), StandardCharsets.US_ASCII);
  }

  @Test
  void acceptsTheSignatureOfTheExactBody() {
    assertThat(StripeSignature.valid(header(NOW.getEpochSecond(), SECRET), PAYLOAD, SECRET, NOW))
        .isTrue();
  }

  @Test
  void acceptsAnyOfSeveralSignaturesWhileASecretRolls() {
    var header =
        header(NOW.getEpochSecond(), "whsec_old")
            + ",v1="
            + header(NOW.getEpochSecond(), SECRET).split("v1=")[1];
    assertThat(StripeSignature.valid(header, PAYLOAD, SECRET, NOW)).isTrue();
  }

  @Test
  void refusesAnotherSecretAChangedBodyOrAStaleTimestamp() {
    var now = NOW.getEpochSecond();
    assertThat(StripeSignature.valid(header(now, "whsec_other"), PAYLOAD, SECRET, NOW)).isFalse();
    assertThat(StripeSignature.valid(header(now, SECRET), "{}".getBytes(), SECRET, NOW)).isFalse();
    assertThat(StripeSignature.valid(header(now - 301, SECRET), PAYLOAD, SECRET, NOW)).isFalse();
  }

  @Test
  void refusesAMissingOrMalformedHeader() {
    assertThat(StripeSignature.valid(null, PAYLOAD, SECRET, NOW)).isFalse();
    assertThat(StripeSignature.valid("v1=abc", PAYLOAD, SECRET, NOW)).isFalse();
    assertThat(StripeSignature.valid("t=soon,v1=abc", PAYLOAD, SECRET, NOW)).isFalse();
    assertThat(StripeSignature.valid(header(NOW.getEpochSecond(), SECRET), PAYLOAD, "", NOW))
        .isFalse();
  }
}
