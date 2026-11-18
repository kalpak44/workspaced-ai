package io.github.kalpak44.workspacedai.api.mail;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Mail sent as the platform's own domain: the routes and their OpenAPI documentation. Every route
 * is for admins only and answers 403 to anyone else.
 *
 * @see MailController
 */
@Tag(name = "Mail", description = "Mail sent from the platform's own addresses")
@RequestMapping("/mail")
public interface MailApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "getMailSettings",
      summary = "Get mail settings",
      description = "The domain mail is sent from, and whether sending is set up.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = MailSettings.class)))
  @ApiResponse(
      responseCode = "403",
      description = "The signed-in user is not an admin.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<MailSettings> settings();

  @Operation(
      operationId = "sendMail",
      summary = "Send a message",
      description =
          "Sends a message written in Markdown as the given address on the platform's domain, "
              + "through Mailjet, formatted and as plain text. Raw HTML in it is shown as text. "
              + "Mailjet accepting it is not delivery: a bounce shows in Mailjet, not "
              + "here.")
  @ApiResponse(
      responseCode = "200",
      description = "Mailjet accepted it.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = SentMail.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "The sender is not a valid local part, a recipient is not an address, there are no "
              + "or more than 50 recipients, or the subject or text is blank or too long.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "403",
      description = "The signed-in user is not an admin.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "502",
      description = "Mailjet refused the message; the detail gives its reason.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "No Mailjet account is configured, or Mailjet cannot be reached right now.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/messages", consumes = JSON, produces = JSON)
  ResponseEntity<SentMail> send(@Valid @RequestBody SendMailRequest request);
}
