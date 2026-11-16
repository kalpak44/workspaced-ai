package io.github.kalpak44.workspacedai.api.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The signed-in user's notifications: the routes and their OpenAPI documentation.
 *
 * @see NotificationController
 */
@Tag(
    name = "Notifications",
    description = "What the app told the user: payments, workspaces made or deleted, low balance")
@RequestMapping("/notifications")
public interface NotificationApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "listNotifications",
      summary = "List notifications",
      description = "The newest 200, newest first.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Notification.class))))
  @GetMapping(produces = JSON)
  ResponseEntity<List<Notification>> list();

  @Operation(
      operationId = "setNotificationsRead",
      summary = "Mark notifications read or unread",
      description = "Ids that are not the user's are ignored. Answers the list as it now stands.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Notification.class))))
  @ApiResponse(
      responseCode = "400",
      description = "The ids are missing, or more than 200.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/read", consumes = JSON, produces = JSON)
  ResponseEntity<List<Notification>> setRead(@Valid @RequestBody ReadRequest request);
}
