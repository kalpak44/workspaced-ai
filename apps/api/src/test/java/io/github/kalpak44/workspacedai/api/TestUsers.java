package io.github.kalpak44.workspacedai.api;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.kalpak44.workspacedai.api.auth.Attribution;
import io.github.kalpak44.workspacedai.api.auth.Role;
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
 * The admin the tests sign in as (db/testdata seeds it), new accounts, and signed-in tokens.
 * Signing in goes through Auth0, which no test reaches, so a token is signed here the way the API
 * signs one after a sign-in.
 */
public final class TestUsers {

  public static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
  public static final String ADMIN_EMAIL = "admin@example.com";

  // The key in the tests' application.properties.
  private static final String SECRET = "test-only-jwt-signing-key-not-for-production";

  private static final NimbusJwtEncoder ENCODER =
      new NimbusJwtEncoder(
          new ImmutableSecret<>(
              new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));

  private TestUsers() {}

  /** The Authorization header value for admin, on whichever test server listens on that port. */
  public static String bearer(int port) {
    return "Bearer " + token(ADMIN, subject(ADMIN_EMAIL));
  }

  /** The Authorization header value for that user, carrying the profile Auth0 has for them. */
  public static String bearer(UserEntity user) {
    return "Bearer " + token(user.getId(), user.getIdpSubject());
  }

  /**
   * An account as an Auth0 sign-in would make it, not yet saved; its subject is "test|" + email.
   */
  public static UserEntity newUser(String email) {
    return new UserEntity(subject(email), Role.USER, Attribution.NONE);
  }

  /** The same, with the names its Auth0 profile has. */
  public static UserEntity newUser(String email, String firstName, String lastName) {
    FakeAuth0Users.name(subject(email), firstName, lastName);
    return newUser(email);
  }

  public static String subject(String email) {
    return "test|" + email;
  }

  public static String token(UUID user, String idpSubject) {
    return token(user, idpSubject, Instant.now());
  }

  public static String token(UUID user, String idpSubject, Instant now) {
    var profile = FakeAuth0Users.profile(idpSubject);
    var claims =
        JwtClaimsSet.builder()
            .issuer(TokenService.ISSUER)
            .subject(user.toString())
            .claim("email", profile.email())
            .claim("given_name", profile.givenName())
            .claim("family_name", profile.familyName())
            .claim("picture", profile.picture())
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofHours(1)))
            .build();
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    return ENCODER.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
