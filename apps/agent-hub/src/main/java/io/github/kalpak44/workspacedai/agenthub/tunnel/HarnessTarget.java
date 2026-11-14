package io.github.kalpak44.workspacedai.agenthub.tunnel;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * What an internal harness call asks for: the VM, and the path on its harness.
 *
 * @param path everything after {@code /harness}, undecoded, with the query string; {@code /} when
 *     nothing follows it
 */
record HarnessTarget(UUID vmId, String path) {

  private static final String PREFIX = "/internal/vms/";
  private static final String HARNESS = "/harness";

  /**
   * @throws TunnelException 404 when the path does not name a VM's harness
   */
  static HarnessTarget of(HttpServletRequest request) {
    // The raw URI, so whatever the API encoded reaches the harness encoded the same way.
    var uri = request.getRequestURI().substring(request.getContextPath().length());
    var slash = uri.indexOf('/', PREFIX.length());
    if (!uri.startsWith(PREFIX) || slash < 0 || !uri.startsWith(HARNESS, slash)) {
      throw notFound();
    }
    UUID vmId;
    try {
      vmId = UUID.fromString(uri.substring(PREFIX.length(), slash));
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
    var path = uri.substring(slash + HARNESS.length());
    if (!path.isEmpty() && !path.startsWith("/")) {
      throw notFound();
    }
    var query = request.getQueryString();
    return new HarnessTarget(
        vmId, (path.isEmpty() ? "/" : path) + (query == null ? "" : "?" + query));
  }

  private static TunnelException notFound() {
    return new TunnelException(HttpStatus.NOT_FOUND, "not a VM's harness");
  }
}
