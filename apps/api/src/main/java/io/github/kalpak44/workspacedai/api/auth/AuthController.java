package io.github.kalpak44.workspacedai.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link AuthApi}. */
@RestController
public class AuthController implements AuthApi {

  private final AuthProperties properties;
  private final HttpServletRequest request;

  public AuthController(AuthProperties properties, HttpServletRequest request) {
    this.properties = properties;
    this.request = request;
  }

  @Override
  public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
    return ResponseEntity.ok(new CsrfResponse(token.getHeaderName(), token.getToken()));
  }

  @Override
  public ResponseEntity<Void> logout() {
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO).toString())
        .build();
  }

  /**
   * The browser is sent to a path of the API's own rather than handed Auth0's URL, so the app never
   * navigates to an address it was given.
   */
  @Override
  public ResponseEntity<Void> endSession() {
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(logoutUrl())).build();
  }

  private String logoutUrl() {
    var app =
        ServletUriComponentsBuilder.fromCurrentRequestUri()
            .replacePath("/")
            .replaceQuery(null)
            .toUriString();
    return properties.issuer()
        + "v2/logout?client_id="
        + URLEncoder.encode(properties.clientId(), StandardCharsets.UTF_8)
        + "&returnTo="
        + URLEncoder.encode(app, StandardCharsets.UTF_8);
  }

  /**
   * Scoped to the API's own path, so the frontend's pages never carry it, and Secure whenever the
   * request arrived over HTTPS.
   */
  static ResponseCookie cookie(HttpServletRequest request, TokenService.Token token) {
    return cookie(request, token.value(), Duration.between(Instant.now(), token.expiresAt()));
  }

  private static ResponseCookie cookie(HttpServletRequest request, String value, Duration maxAge) {
    var path = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
    return ResponseCookie.from(SecurityConfiguration.COOKIE, value)
        .httpOnly(true)
        .secure(request.isSecure())
        .sameSite("Strict")
        .path(path)
        .maxAge(maxAge)
        .build();
  }
}
