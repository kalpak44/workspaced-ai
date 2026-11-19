package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
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
          "Clears the token cookie and revokes every session of its account, on every device. "
              + "A browser then opens /auth/end-session to end the Auth0 session too, which lands "
              + "on the public site.")
  @ApiResponse(responseCode = "204", description = "Signed out.")
  @PostMapping("/logout")
  ResponseEntity<Void> logout();

  @Operation(
      operationId = "endSession",
      summary = "End the Auth0 session",
      description =
          "Redirects the browser to Auth0's logout, which comes back to the app. Without it "
              + "the next sign-in would pass without asking for a password.")
  @ApiResponse(responseCode = "302", description = "On to Auth0.")
  @GetMapping("/end-session")
  ResponseEntity<Void> endSession();

  @Operation(
      operationId = "resendVerificationEmail",
      summary = "Send the verification email again",
      description =
          "For someone who signed up and has not verified their email: the sign-in sets a "
              + "short-lived cookie naming them, and this has Auth0 send its verification email "
              + "again, at most once a minute.")
  @ApiResponse(responseCode = "204", description = "Sent.")
  @ApiResponse(
      responseCode = "401",
      description = "No cookie, or it expired: sign in again for a new one.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "429",
      description = "One was sent less than a minute ago.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping("/verification-email")
  ResponseEntity<Void> resendVerificationEmail();
}
