package io.github.kalpak44.workspacedai.agenthub.tunnel;

import jakarta.websocket.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/** What both socket endpoints do around a handshake. */
final class Handshakes {

  private Handshakes() {}

  /** Refuses a handshake before the upgrade with a problem detail, like every other error. */
  static void refuse(
      ServerHttpRequest request,
      ServerHttpResponse response,
      JsonMapper json,
      HttpStatusCode status,
      String detail) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setInstance(URI.create(request.getURI().getRawPath()));
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    try {
      response.getBody().write(json.writeValueAsBytes(problem));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static jakarta.servlet.http.HttpServletRequest servlet(ServerHttpRequest request) {
    return ((ServletServerHttpRequest) request).getServletRequest();
  }

  /**
   * No idle timeout: a terminal can sit quiet for hours, and an idle worker is kept alive by the
   * supervisor's pings rather than cut by this side.
   */
  static void neverIdle(WebSocketSession session) {
    if (session instanceof NativeWebSocketSession nativeSession
        && nativeSession.getNativeSession(Session.class) instanceof Session standard) {
      standard.setMaxIdleTimeout(0);
    }
  }
}
