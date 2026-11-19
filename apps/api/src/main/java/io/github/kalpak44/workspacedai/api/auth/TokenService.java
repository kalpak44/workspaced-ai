package io.github.kalpak44.workspacedai.api.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the session JWTs the resource server validates once Auth0 has signed the user in: the
 * user's id as subject, and the profile Auth0 vouched for, which stays in the user's own browser.
 */
@Service
public class TokenService {

  public static final String ISSUER = "workspaced-ai";

  // Long enough to find the email and ask again, short enough not to linger.
  private static final Duration VERIFICATION_TTL = Duration.ofMinutes(30);

  private final JwtEncoder encoder;
  private final AuthProperties properties;
  private final Clock clock;

  public TokenService(JwtEncoder encoder, AuthProperties properties) {
    this.encoder = encoder;
    this.properties = properties;
    this.clock = Clock.systemUTC();
  }

  public record Token(String value, Instant expiresAt) {}

  public Token issue(UserEntity user, Auth0Users.Profile profile) {
    var now = clock.instant();
    var expiresAt = now.plus(properties.tokenTtl());
    return sign(
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(user.getId().toString())
            .claim(CurrentUser.EMAIL, profile.email())
            .claim(CurrentUser.GIVEN_NAME, profile.givenName())
            .claim(CurrentUser.FAMILY_NAME, profile.familyName())
            .claim(CurrentUser.PICTURE, profile.picture())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .build(),
        expiresAt);
  }

  /**
   * Proves which Auth0 user signed up and has not verified their email yet, so they can ask for the
   * email again. Its subject is the Auth0 user, never an account, and it signs no one in.
   */
  public Token issueVerification(String idpSubject) {
    var now = clock.instant();
    var expiresAt = now.plus(VERIFICATION_TTL);
    return sign(
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(idpSubject)
            .claim(CurrentUser.USE, CurrentUser.VERIFY_EMAIL)
            .issuedAt(now)
            .expiresAt(expiresAt)
            .build(),
        expiresAt);
  }

  private Token sign(JwtClaimsSet claims, Instant expiresAt) {
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    var value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new Token(value, expiresAt);
  }
}
