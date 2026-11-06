package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The signed-in user's own account: the routes and their OpenAPI documentation.
 *
 * @see AccountController
 */
@Tag(name = "Account", description = "The signed-in user's profile and password")
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
          "Replaces the first name, last name and avatar. Surrounding whitespace is trimmed from "
              + "the names.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Account.class)))
  @ApiResponse(
      responseCode = "400",
      description = "A name is too long, or the avatar is not a JPG, PNG, WebP or GIF of 2 MB.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/profile", consumes = JSON, produces = JSON)
  ResponseEntity<Account> updateProfile(@Valid @RequestBody ProfileRequest request);

  @Operation(
      operationId = "changePassword",
      summary = "Change the password",
      description =
          "Replaces the password once the current one is confirmed. Tokens already issued stay "
              + "valid until they expire.")
  @ApiResponse(responseCode = "204", description = "Changed.")
  @ApiResponse(
      responseCode = "400",
      description = "The new password is shorter than 8 or longer than 72 characters.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "422",
      description = "The current password is wrong; nothing was changed.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/password", consumes = JSON)
  ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordRequest request);
}
