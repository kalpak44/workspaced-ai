package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Signing in and out: the routes and their OpenAPI documentation.
 *
 * @see AuthController
 */
@Tag(name = "Authentication", description = "Signing in and out")
@SecurityRequirements
@RequestMapping("/auth")
public interface AuthApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "login",
      summary = "Sign in",
      description =
          "Checks the username and password and answers with a JWT. The same token is set as an "
              + "HttpOnly, SameSite=Strict cookie, which is all a browser needs; other clients "
              + "send it as `Authorization: Bearer …`. A fresh installation has admin/admin.")
  @ApiResponse(
      responseCode = "200",
      description = "Signed in.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = LoginResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The username or password is blank.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "401",
      description = "No user has that username and password.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/login", consumes = JSON, produces = JSON)
  ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request);

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
      description = "Clears the token cookie. A token kept elsewhere stays valid until it expires.")
  @ApiResponse(responseCode = "204", description = "Signed out.")
  @PostMapping("/logout")
  ResponseEntity<Void> logout();
}
