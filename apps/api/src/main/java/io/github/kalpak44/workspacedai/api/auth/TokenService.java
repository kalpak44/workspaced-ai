package io.github.kalpak44.workspacedai.api.auth;

import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues the JWTs the resource server validates; the subject is the user's id. */
@Service
public class TokenService {

  static final String ISSUER = "workspaced-ai";

  private final JwtEncoder encoder;
  private final AuthProperties properties;
  private final Clock clock;

  public TokenService(JwtEncoder encoder, AuthProperties properties) {
    this.encoder = encoder;
    this.properties = properties;
    this.clock = Clock.systemUTC();
  }

  public record Token(String value, Instant expiresAt) {}

  public Token issue(UserEntity user) {
    var now = clock.instant();
    var expiresAt = now.plus(properties.tokenTtl());
    var claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(user.getId().toString())
            .claim(CurrentUser.USERNAME_CLAIM, user.getUsername())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .build();
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    var value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new Token(value, expiresAt);
  }
}
