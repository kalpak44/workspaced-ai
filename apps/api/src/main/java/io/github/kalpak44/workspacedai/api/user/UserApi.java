package io.github.kalpak44.workspacedai.api.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Everyone who can sign in: the routes and their OpenAPI documentation.
 *
 * @see UserController
 */
@Tag(name = "Users", description = "The accounts that can sign in")
@RequestMapping("/users")
public interface UserApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "listUsers",
      summary = "List users",
      description =
          "One page of users, newest first unless sorted otherwise. Sort by username, firstName, "
              + "lastName or createdAt, e.g. sort=firstName,asc&sort=lastName,asc; the id breaks "
              + "ties. A page holds at most 100.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = PagedModel.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The sort names a field users cannot be sorted by.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<PagedModel<User>> list(
      @ParameterObject
          @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable);

  @Operation(
      operationId = "createUser",
      summary = "Create a user",
      description =
          "Creates a user who signs in with this username and password. Surrounding whitespace is "
              + "trimmed from the username and names.")
  @ApiResponse(
      responseCode = "201",
      description = "Created.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = User.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "The username is blank, a name is longer than 100 characters, or the password is "
              + "shorter than 8 or longer than 72 characters.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Another user has that username; nothing was created.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(consumes = JSON, produces = JSON)
  ResponseEntity<User> create(@Valid @RequestBody CreateUserRequest request);

  @Operation(
      operationId = "deleteUser",
      summary = "Delete a user",
      description =
          "Deletes the user and every workspace they own, stopping each workspace first. Answers "
              + "once they are gone. A token already issued to them stops working.")
  @ApiResponse(responseCode = "204", description = "Deleted.")
  @ApiResponse(
      responseCode = "404",
      description = "No user has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "That is the signed-in user, who cannot delete themselves.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@Parameter(description = "The user's id") @PathVariable UUID id);
}
