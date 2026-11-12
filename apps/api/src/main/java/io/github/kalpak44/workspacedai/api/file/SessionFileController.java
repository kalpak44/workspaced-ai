package io.github.kalpak44.workspacedai.api.file;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link SessionFileApi}. */
@RestController
public class SessionFileController implements SessionFileApi {

  private final SessionFileService service;

  public SessionFileController(SessionFileService service) {
    this.service = service;
  }

  /** The body is streamed straight into the workspace, so no upload is held in the API. */
  @Override
  public ResponseEntity<SessionFile> upload(
      UUID workspaceId, UUID sessionId, String name, HttpServletRequest request)
      throws IOException {
    var file =
        service.upload(
            workspaceId, sessionId, name, request.getInputStream(), request.getContentLengthLong());
    return ResponseEntity.status(HttpStatus.CREATED).body(file);
  }

  @Override
  public ResponseEntity<FileLink> link(UUID workspaceId, UUID sessionId, FileLinkRequest request) {
    var link = service.link(workspaceId, sessionId, request.path());
    var url =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/files/{token}")
            .buildAndExpand(link.token())
            .toUri();
    return ResponseEntity.ok(new FileLink(url, link.expiresAt()));
  }

  @Override
  public ResponseEntity<StreamingResponseBody> download(String token) {
    var download =
        service
            .open(token)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "the link is invalid or has expired"));
    var type = URLConnection.guessContentTypeFromName(download.name());
    var headers = new HttpHeaders();
    headers.setContentDisposition(
        ContentDisposition.attachment().filename(download.name(), StandardCharsets.UTF_8).build());
    headers.setContentType(
        type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type));
    headers.setCacheControl("private, no-store");
    if (download.file().length() >= 0) {
      headers.setContentLength(download.file().length());
    }
    StreamingResponseBody body =
        output -> {
          try (var content = download.file().content()) {
            content.transferTo(output);
          }
        };
    return ResponseEntity.ok().headers(headers).body(body);
  }
}
