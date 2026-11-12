package io.github.kalpak44.workspacedai.api.file;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionEntity;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Files between the user and a session's agent. Uploads land in the session's own folder, under
 * {@code uploads/}, where the agent works; a file the agent made is handed back through a signed
 * link that lasts minutes and dies with the session. Nothing is stored outside the workspace.
 */
@Service
public class SessionFileService {

  static final String UPLOADS = "uploads";

  /** Large enough for documents and datasets, small enough to stream through the API. */
  static final long MAX_UPLOAD = 100L * 1024 * 1024;

  // A plain file name: no folder, no leading dot, nothing a shell or a URL would read specially.
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ ()+-]{0,199}");

  private final WorkspaceSessionRepository sessions;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final FileLinks links;

  public SessionFileService(
      WorkspaceSessionRepository sessions,
      WorkspaceRuntime runtime,
      Harness harness,
      FileLinks links) {
    this.sessions = sessions;
    this.runtime = runtime;
    this.harness = harness;
    this.links = links;
  }

  /** Replaces a file of the same name, as a second upload of it would be meant to. */
  public SessionFile upload(
      UUID workspaceId, UUID sessionId, String name, InputStream content, long length) {
    if (name == null || !NAME.matcher(name).matches() || name.contains("..")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "a file name is up to 200 letters, digits, spaces and . _ ( ) + -, starting with a"
              + " letter or digit");
    }
    if (length < 0) {
      throw new ResponseStatusException(
          HttpStatus.LENGTH_REQUIRED, "send the file's length in Content-Length");
    }
    if (length > MAX_UPLOAD) {
      throw new ResponseStatusException(
          HttpStatus.CONTENT_TOO_LARGE, "a file is at most " + MAX_UPLOAD / 1024 / 1024 + " MB");
    }
    var session = session(workspaceId, sessionId);
    var relative = UPLOADS + "/" + name;
    harness.uploadFile(endpoint(workspaceId), session.getPath() + "/" + relative, content, length);
    return new SessionFile(name, relative, length);
  }

  /** The file must exist now; the link is checked again, against the session, when it is used. */
  public IssuedLink link(UUID workspaceId, UUID sessionId, String path) {
    var session = session(workspaceId, sessionId);
    var relative = relative(session, path);
    try (var file =
        harness
            .downloadFile(endpoint(workspaceId), session.getPath(), relative)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "the session has no file " + relative))
            .content()) {
      // Only its existence was in question.
    } catch (IOException e) {
      throw new HarnessUnavailableException("could not read the file", e);
    }
    var expiresAt = links.expiry();
    return new IssuedLink(links.sign(workspaceId, sessionId, relative, expiresAt), expiresAt);
  }

  /** The token a link carries, and when it stops working. */
  public record IssuedLink(String token, Instant expiresAt) {}

  /** Empty for a link that is forged, expired, or whose session or file is gone. */
  public Optional<Download> open(String token) {
    return links
        .verify(token)
        .flatMap(
            grant ->
                sessions
                    .findByIdAndWorkspaceId(grant.sessionId(), grant.workspaceId())
                    .flatMap(
                        session ->
                            runtime
                                .harness(grant.workspaceId())
                                .flatMap(
                                    endpoint ->
                                        harness.downloadFile(
                                            endpoint, session.getPath(), grant.path())))
                    .map(
                        file ->
                            new Download(
                                Path.of(grant.path()).getFileName().toString(),
                                file,
                                grant.expiresAt())));
  }

  /** A file being sent to whoever holds its link. */
  public record Download(String name, Harness.HarnessFile file, Instant expiresAt) {}

  /**
   * The path inside the session's folder, as the agent may write it: relative, or absolute under
   * the folder. Anything that would leave the folder is refused.
   */
  static String relative(WorkspaceSessionEntity session, String path) {
    var root = Path.of(session.getPath()).normalize();
    var given = Path.of(path.strip());
    var resolved = (given.isAbsolute() ? given : root.resolve(given)).normalize();
    if (!resolved.startsWith(root) || resolved.equals(root)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "a file link must name a file in the session's folder");
    }
    return root.relativize(resolved).toString();
  }

  private WorkspaceSessionEntity session(UUID workspaceId, UUID sessionId) {
    return sessions
        .findByIdAndWorkspaceId(sessionId, workspaceId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "the workspace has no session " + sessionId));
  }

  private HarnessEndpoint endpoint(UUID workspaceId) {
    return runtime
        .harness(workspaceId)
        .orElseThrow(
            () -> new HarnessUnavailableException("the workspace's harness is not running"));
  }
}
