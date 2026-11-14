package io.github.kalpak44.workspacedai.agenthub.tunnel;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.JsonNode;

/** A call to a VM's harness that the tunnel could not carry, with the status to answer it with. */
public class TunnelException extends RuntimeException {

  private final HttpStatusCode status;

  public TunnelException(HttpStatusCode status, String message) {
    super(message);
    this.status = status;
  }

  static TunnelException notConnected() {
    return new TunnelException(
        HttpStatus.SERVICE_UNAVAILABLE, "the workspace's harness is not connected");
  }

  static TunnelException dropped(String message) {
    return new TunnelException(HttpStatus.BAD_GATEWAY, message);
  }

  /** The supervisor's {@code error} message; a status that is not an error answers 502. */
  static TunnelException of(JsonNode error) {
    var status = error.path("status").asInt(502);
    return new TunnelException(
        HttpStatusCode.valueOf(status >= 400 && status <= 599 ? status : 502),
        error.path("message").asString("the harness could not be reached"));
  }

  public HttpStatusCode status() {
    return status;
  }
}
