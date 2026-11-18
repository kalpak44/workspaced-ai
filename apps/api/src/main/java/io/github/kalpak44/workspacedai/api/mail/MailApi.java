package io.github.kalpak44.workspacedai.api.mail;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
      description =
          "The domain mail is sent from, whether sending is set up, and every address mail can "
              + "be sent from with its template.")
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
          "Sends a message written in Markdown as one of the listed senders, through Mailjet: "
              + "formatted inside the site's header and footer with the sender's signature and "
              + "footer line, and as plain text. Raw HTML in it is shown as text. Mailjet accepting it is not delivery: a bounce shows in Mailjet, not "
              + "here.")
  @ApiResponse(
      responseCode = "200",
      description = "Mailjet accepted it.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = SentMail.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "The sender is not one of those listed, a recipient is not an address, there are no "
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

  @Operation(
      operationId = "previewMail",
      summary = "Preview a message",
      description =
          "The HTML part of a message as it would be sent, sending nothing. A template in the "
              + "request is shown instead of the sender's saved one, so an edit can be seen "
              + "before it is saved.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = MailPreview.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The sender is not one of those listed, or a field is too long.",
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
  @PostMapping(path = "/preview", consumes = JSON, produces = JSON)
  ResponseEntity<MailPreview> preview(@Valid @RequestBody PreviewMailRequest request);

  @Operation(
      operationId = "updateMailTemplate",
      summary = "Change a sender's template",
      description =
          "Sets the signature and footer line that one sender's messages go out with, "
              + "the platform's own notifications included when it is the no-reply sender. "
              + "Surrounding whitespace is trimmed.")
  @ApiResponse(
      responseCode = "200",
      description = "Saved.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = MailSender.class)))
  @ApiResponse(
      responseCode = "400",
      description = "A field is too long, or the footer holds a line break.",
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
      responseCode = "404",
      description = "No sender has that local part.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/senders/{localPart}", consumes = JSON, produces = JSON)
  ResponseEntity<MailSender> updateTemplate(
      @Parameter(description = "The sender's address before the @", example = "support")
          @PathVariable
          String localPart,
      @Valid @RequestBody MailTemplateRequest request);
}
