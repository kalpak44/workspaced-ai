package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The signed-in user's own account: the routes and their OpenAPI documentation.
 *
 * @see AccountController
 */
@Tag(name = "Account", description = "The signed-in user's profile and password reset")
@RequestMapping("/account")
public interface AccountApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "getAccount",
      summary = "Get the signed-in account",
      description = "Who is signed in, with their profile. 401 means no one is.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Account.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Not signed in, or the sign-in has expired.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<Account> get();

  @Operation(
      operationId = "updateProfile",
      summary = "Update the profile",
      description =
          "Replaces the first and last name in the user's Auth0 profile. Surrounding whitespace "
              + "is trimmed.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Account.class)))
  @ApiResponse(
      responseCode = "400",
      description = "A name is longer than 100 characters.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/profile", consumes = JSON, produces = JSON)
  ResponseEntity<Account> updateProfile(@Valid @RequestBody ProfileRequest request);

  @Operation(
      operationId = "saveTour",
      summary = "Save where the guided tour is",
      description =
          "Replaces the account's tour progress, so the tour resumes in any browser. It is only a"
              + " bookmark for the web app: it grants nothing, and the one free demo is enforced"
              + " when a workspace is created.")
  @ApiResponse(responseCode = "204", description = "Saved.")
  @ApiResponse(
      responseCode = "400",
      description = "A step outside 0–100, or a value longer than 100 characters.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/tour", consumes = JSON)
  ResponseEntity<Void> saveTour(@Valid @RequestBody TourProgress progress);

  @Operation(
      operationId = "endTour",
      summary = "End the guided tour",
      description = "Forgets the tour's progress, once it is finished or can no longer go on.")
  @ApiResponse(responseCode = "204", description = "Ended, or none was under way.")
  @DeleteMapping("/tour")
  ResponseEntity<Void> endTour();

  @Operation(
      operationId = "sendPasswordReset",
      summary = "Send a password reset email",
      description =
          "Auth0 mails the signed-in user a link to choose a new password. The password is never "
              + "sent to this API.")
  @ApiResponse(responseCode = "204", description = "Sent.")
  @ApiResponse(
      responseCode = "502",
      description = "Auth0 could not be reached, or refused to send it.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping("/password-reset")
  ResponseEntity<Void> sendPasswordReset();
}
