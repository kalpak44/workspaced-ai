package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Signing in and out: the routes and their OpenAPI documentation.
 *
 * @see AuthController
 */
@Tag(
    name = "Authentication",
    description =
        "Signing out. Signing in is a browser redirect to /oauth2/authorization/auth0, which "
            + "Auth0 answers with its own login page.")
@SecurityRequirements
@RequestMapping("/auth")
public interface AuthApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "getCsrfToken",
      summary = "Get a CSRF token",
      description =
          "For browsers signed in by cookie: a change they send (any method but GET) must carry "
              + "this token in the header named here. Sets the HttpOnly XSRF-TOKEN cookie it is "
              + "checked against. Clients sending a bearer token need none.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = CsrfResponse.class)))
  @GetMapping(path = "/csrf", produces = JSON)
  ResponseEntity<CsrfResponse> csrf(@Parameter(hidden = true) CsrfToken token);

  @Operation(
      operationId = "logout",
      summary = "Sign out",
      description =
          "Clears the token cookie and answers where to send the browser next, which ends the "
              + "Auth0 session too; otherwise the next sign-in would pass silently. A token kept "
              + "elsewhere stays valid until it expires.")
  @ApiResponse(
      responseCode = "200",
      description = "Signed out.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = LogoutResponse.class)))
  @PostMapping(path = "/logout", produces = JSON)
  ResponseEntity<LogoutResponse> logout();
}
