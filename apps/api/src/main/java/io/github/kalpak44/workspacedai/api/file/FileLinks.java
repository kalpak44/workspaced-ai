package io.github.kalpak44.workspacedai.api.file;

import io.github.kalpak44.workspacedai.api.auth.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signs and checks the tokens in download links. The link works without signing in, so the token is
 * the whole credential: it names one file of one session and expires. It is signed with the login
 * key under a label of its own, so it can never pass as a login token or the reverse.
 */
@Component
public class FileLinks {

  static final Duration TTL = Duration.ofMinutes(15);
  private static final String ALGORITHM = "HmacSHA256";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  /** What a token names; the session must still exist when it is used. */
  record Grant(UUID workspaceId, UUID sessionId, String path, Instant expiresAt) {}

  private final SecretKeySpec key;
  private final JsonMapper json;
  private final Clock clock;

  @Autowired
  public FileLinks(AuthProperties auth, JsonMapper json) {
    this(auth, json, Clock.systemUTC());
  }

  FileLinks(AuthProperties auth, JsonMapper json, Clock clock) {
    var secret = ("workspaced-file-link:" + auth.jwtSecret()).getBytes(StandardCharsets.UTF_8);
    this.key = new SecretKeySpec(secret, ALGORITHM);
    this.json = json;
    this.clock = clock;
  }

  String sign(UUID workspaceId, UUID sessionId, String path, Instant expiresAt) {
    var payload =
        ENCODER.encodeToString(
            json.writeValueAsBytes(new Grant(workspaceId, sessionId, path, expiresAt)));
    return payload + "." + ENCODER.encodeToString(mac(payload));
  }

  Instant expiry() {
    return clock.instant().plus(TTL);
  }

  /** Empty for a token that is malformed, forged or expired. */
  Optional<Grant> verify(String token) {
    var dot = token.indexOf('.');
    if (dot < 1) {
      return Optional.empty();
    }
    var payload = token.substring(0, dot);
    try {
      if (!MessageDigest.isEqual(mac(payload), DECODER.decode(token.substring(dot + 1)))) {
        return Optional.empty();
      }
      var grant = json.readValue(DECODER.decode(payload), Grant.class);
      return grant.expiresAt().isAfter(clock.instant()) ? Optional.of(grant) : Optional.empty();
    } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
      return Optional.empty();
    }
  }

  private byte[] mac(String payload) {
    try {
      var mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }
}
