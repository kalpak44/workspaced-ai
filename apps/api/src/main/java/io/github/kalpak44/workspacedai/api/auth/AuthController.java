package io.github.kalpak44.workspacedai.api.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Routes, validation and documentation are declared on {@link AuthApi}. */
@RestController
public class AuthController implements AuthApi {

  // Proves which unverified sign-up is asking for its email again; see TokenService.
  static final String VERIFICATION_COOKIE = "__Host-workspaced_verify";

  private final AuthProperties properties;
  private final AuthService auth;
  private final HttpServletRequest request;

  public AuthController(AuthProperties properties, AuthService auth, HttpServletRequest request) {
    this.properties = properties;
    this.auth = auth;
    this.request = request;
  }

  @Override
  public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
    return ResponseEntity.ok(new CsrfResponse(token.getHeaderName(), token.getToken()));
  }

  @Override
  public ResponseEntity<Void> logout() {
    var session = cookie(request, SecurityConfiguration.COOKIE);
    if (session != null) {
      auth.signOut(session);
    }
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
        .build();
  }

  /**
   * The browser is sent to a path of the API's own rather than handed Auth0's URL, so the app never
   * navigates to an address it was given. It clears the session cookie too, so signing out holds
   * even when the POST before it failed.
   */
  @Override
  public ResponseEntity<Void> endSession() {
    var returnTo = properties.siteUrl() + "/";
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
        .location(URI.create(Auth0SignIn.logoutUrl(properties, returnTo)))
        .build();
  }

  @Override
  public ResponseEntity<Void> resendVerificationEmail() {
    var proof = verificationCookie(request);
    if (proof == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in again");
    }
    auth.resendVerificationEmail(proof);
    return ResponseEntity.noContent().build();
  }

  static ResponseCookie verificationCookie(TokenService.Token token) {
    return ResponseCookie.from(VERIFICATION_COOKIE, token.value())
        .httpOnly(true)
        .secure(true)
        // Must return on Auth0's cross-site callback after email verification.
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.between(Instant.now(), token.expiresAt()))
        .build();
  }

  static ResponseCookie clearVerificationCookie() {
    return ResponseCookie.from(VERIFICATION_COOKIE, "")
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.ZERO)
        .build();
  }

  static String verificationCookie(HttpServletRequest request) {
    return cookie(request, VERIFICATION_COOKIE);
  }

  private static String cookie(HttpServletRequest request, String name) {
    if (request.getCookies() == null) {
      return null;
    }
    return Arrays.stream(request.getCookies())
        .filter(c -> name.equals(c.getName()) && !c.getValue().isBlank())
        .map(Cookie::getValue)
        .findFirst()
        .orElse(null);
  }

  /** The session cookie: HttpOnly, and __Host- like every cookie here. */
  static ResponseCookie cookie(TokenService.Token token) {
    return cookie(token.value(), Duration.between(Instant.now(), token.expiresAt()));
  }

  /** __Host-, so Secure, Path=/ and no Domain: no sibling origin can set or shadow it. */
  private static ResponseCookie cookie(String value, Duration maxAge) {
    return ResponseCookie.from(SecurityConfiguration.COOKIE, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(maxAge)
        .build();
  }
}
