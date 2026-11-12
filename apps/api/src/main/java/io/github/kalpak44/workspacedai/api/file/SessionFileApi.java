package io.github.kalpak44.workspacedai.api.file;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Session files API: uploads into a session's folder, and the signed links that hand a file the
 * agent made back to the user.
 *
 * @see SessionFileController
 */
@Tag(name = "Session files", description = "Files between the user and a session's agent")
public interface SessionFileApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "uploadSessionFile",
      summary = "Upload a file into a session's folder",
      description =
          "The request body is the file itself, at most 100 MB, with its length in "
              + "Content-Length. It lands in uploads/ in the session's folder, replacing a file of "
              + "the same name, where the session's agent can read it.")
  @ApiResponse(
      responseCode = "201",
      description = "Uploaded.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = SessionFile.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The name is not a plain file name.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "413",
      description = "The file is larger than 100 MB.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(
      path = "/workspaces/{workspaceId}/sessions/{sessionId}/files/{name}",
      consumes = "*/*",
      produces = JSON)
  ResponseEntity<SessionFile> upload(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Parameter(description = "Session id") @PathVariable UUID sessionId,
      @Parameter(description = "The file's name", example = "report.pdf") @PathVariable String name,
      HttpServletRequest request)
      throws IOException;

  @Operation(
      operationId = "linkSessionFile",
      summary = "Link to download a file from a session's folder",
      description =
          "A link that works without signing in for 15 minutes, and only while the session "
              + "exists. The file must exist now.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = FileLink.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The path leaves the session's folder.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No such session, or no such file in it.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(
      path = "/workspaces/{workspaceId}/sessions/{sessionId}/files/links",
      consumes = JSON,
      produces = JSON)
  ResponseEntity<FileLink> link(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Parameter(description = "Session id") @PathVariable UUID sessionId,
      @Valid @RequestBody FileLinkRequest request);

  @Operation(
      operationId = "downloadFile",
      summary = "Download a file through its link",
      description =
          "Needs no sign-in: the token is the credential. Always sent as an attachment, so a file "
              + "the agent wrote is never rendered as a page of this site.")
  @ApiResponse(responseCode = "200", description = "The file.")
  @ApiResponse(
      responseCode = "404",
      description = "The link is invalid or expired, or its session or file is gone.")
  @GetMapping("/files/{token}")
  ResponseEntity<StreamingResponseBody> download(
      @Parameter(description = "From a link") @PathVariable String token);
}
