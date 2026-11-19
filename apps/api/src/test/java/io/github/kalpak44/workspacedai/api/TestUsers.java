package io.github.kalpak44.workspacedai.api;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import io.github.kalpak44.workspacedai.api.auth.TokenService;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The default admin account, and signed-in tokens. Signing in goes through Auth0, which no test
 * reaches, so a token is signed here the way the API signs one after a sign-in.
 */
public final class TestUsers {

  public static final UUID ADMIN = UserEntity.DEFAULT_ADMIN_ID;
  public static final String ADMIN_EMAIL = "pavel.usanli@gmail.com";

  // The key in the tests' application.properties.
  private static final String SECRET = "test-only-jwt-signing-key-not-for-production";

  private static final NimbusJwtEncoder ENCODER =
      new NimbusJwtEncoder(
          new ImmutableSecret<>(
              new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));

  private TestUsers() {}

  /** The Authorization header value for admin, on whichever test server listens on that port. */
  public static String bearer(int port) {
    return "Bearer " + token(ADMIN, 0);
  }

  /** The Authorization header value for that user as they are now. */
  public static String bearer(UserEntity user) {
    return "Bearer " + token(user.getId(), user.getTokenVersion());
  }

  public static String token(UUID user, int version) {
    var now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer(TokenService.ISSUER)
            .subject(user.toString())
            .claim(CurrentUser.VERSION_CLAIM, version)
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofHours(1)))
            .build();
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    return ENCODER.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
