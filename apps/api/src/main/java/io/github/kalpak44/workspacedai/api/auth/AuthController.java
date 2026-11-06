package io.github.kalpak44.workspacedai.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link AuthApi}. */
@RestController
public class AuthController implements AuthApi {

  private final AuthService service;
  private final HttpServletRequest request;

  public AuthController(AuthService service, HttpServletRequest request) {
    this.service = service;
    this.request = request;
  }

  @Override
  public ResponseEntity<LoginResponse> login(LoginRequest body) {
    var signIn = service.login(body.username(), body.password());
    var token = signIn.token();
    var lifetime = Duration.between(Instant.now(), token.expiresAt());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie(token.value(), lifetime).toString())
        .body(new LoginResponse(signIn.account(), token.value(), token.expiresAt()));
  }

  @Override
  public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
    return ResponseEntity.ok(new CsrfResponse(token.getHeaderName(), token.getToken()));
  }

  @Override
  public ResponseEntity<Void> logout() {
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
        .build();
  }

  /**
   * Scoped to the API's own path, so the frontend's pages never carry it, and Secure whenever the
   * request arrived over HTTPS.
   */
  private ResponseCookie cookie(String value, Duration maxAge) {
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
