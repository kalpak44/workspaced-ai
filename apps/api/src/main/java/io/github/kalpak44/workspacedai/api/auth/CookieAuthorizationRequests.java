package io.github.kalpak44.workspacedai.api.auth;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the authorization request in flight in an encrypted cookie rather than a server session, so
 * whichever instance Auth0's callback reaches can finish the sign-in. Encrypted, not just signed,
 * because it holds the PKCE verifier.
 */
final class CookieAuthorizationRequests
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

  static final String COOKIE = "__Host-workspaced_sign_in";

  // Long enough to type a password and an MFA code at Auth0, short enough not to linger.
  private static final Duration LIFETIME = Duration.ofMinutes(10);

  private final byte[] key;
  private final JsonMapper json;

  /**
   * @param secret the key every instance shares; hashed with a label of its own, so this cipher's
   *     key is never the one the session tokens are signed with
   */
  CookieAuthorizationRequests(String secret, JsonMapper json) {
    this.key = sha256("workspaced-sign-in-request:" + secret);
    this.json = json;
  }

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    var value = cookie(request);
    return value == null ? null : decrypt(value);
  }

  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest authorization,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (authorization == null) {
      response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
      return;
    }
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(encrypt(authorization), LIFETIME));
  }

  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    var authorization = loadAuthorizationRequest(request);
    response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
    return authorization;
  }

  private String encrypt(OAuth2AuthorizationRequest authorization) {
    var stored =
        new Stored(
            authorization.getAuthorizationUri(),
            authorization.getClientId(),
            authorization.getRedirectUri(),
            List.copyOf(authorization.getScopes()),
            authorization.getState(),
            strings(authorization.getAdditionalParameters()),
            strings(authorization.getAttributes()),
            authorization.getAuthorizationRequestUri());
    var jwe =
        new JWEObject(
            new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256GCM),
            new Payload(json.writeValueAsString(stored)));
    try {
      jwe.encrypt(new DirectEncrypter(key));
    } catch (JOSEException e) {
      throw new IllegalStateException("cannot encrypt the sign-in request", e);
    }
    return jwe.serialize();
  }

  /** Null for anything this instance did not write, or that was altered: the sign-in fails. */
  private OAuth2AuthorizationRequest decrypt(String value) {
    Stored stored;
    try {
      var jwe = JWEObject.parse(value);
      jwe.decrypt(new DirectDecrypter(key));
      stored = json.readValue(jwe.getPayload().toString(), Stored.class);
    } catch (ParseException | JOSEException | JacksonException _) {
      return null;
    }
    return OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri(stored.authorizationUri())
        .clientId(stored.clientId())
        .redirectUri(stored.redirectUri())
        .scopes(new LinkedHashSet<>(stored.scopes()))
        .state(stored.state())
        .additionalParameters(Map.copyOf(stored.additionalParameters()))
        .attributes(Map.copyOf(stored.attributes()))
        .authorizationRequestUri(stored.authorizationRequestUri())
        .build();
  }

  /**
   * __Host-, so no sibling origin can plant one to sign a victim in as someone else; Lax so it
   * rides along on the redirect back from Auth0.
   */
  private static String cookie(String value, Duration maxAge) {
    return ResponseCookie.from(COOKIE, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build()
        .toString();
  }

  private static String cookie(HttpServletRequest request) {
    if (request.getCookies() == null) {
      return null;
    }
    return Arrays.stream(request.getCookies())
        .filter(c -> COOKIE.equals(c.getName()) && !c.getValue().isBlank())
        .map(Cookie::getValue)
        .findFirst()
        .orElse(null);
  }

  /** Every parameter and attribute Spring sets here is a string; anything else is a bug. */
  private static Map<String, String> strings(Map<String, Object> values) {
    var copy = new LinkedHashMap<String, String>();
    values.forEach(
        (name, value) -> {
          if (!(value instanceof String text)) {
            throw new IllegalStateException(name + " is not a string");
          }
          copy.put(name, text);
        });
    return copy;
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Stored(
      String authorizationUri,
      String clientId,
      String redirectUri,
      List<String> scopes,
      String state,
      Map<String, String> additionalParameters,
      Map<String, String> attributes,
      String authorizationRequestUri) {}
}
