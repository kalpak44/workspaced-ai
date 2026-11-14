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
record HarnessTarget(UUID vmId, String path, boolean web) {

  private static final String PREFIX = "/internal/vms/";
  private static final String HARNESS = "/harness";

  /**
   * @throws TunnelException 404 when the path does not name a VM's harness
   */
  static HarnessTarget of(HttpServletRequest request) {
    // The raw URI, so whatever the API encoded reaches the harness encoded the same way.
    var uri = request.getRequestURI().substring(request.getContextPath().length());
    var slash = uri.indexOf('/', PREFIX.length());
    if (!uri.startsWith(PREFIX)
        || slash < 0
        || !(uri.startsWith(HARNESS, slash) || uri.startsWith("/web", slash))) {
      throw notFound();
    }
    return target(request, uri, slash, uri.substring(PREFIX.length(), slash));
  }

  /** The controller's route binds the VM, so it passes the segment Spring already resolved. */
  static HarnessTarget of(HttpServletRequest request, String boundVmId) {
    var uri = request.getRequestURI().substring(request.getContextPath().length());
    var slash = uri.indexOf('/', PREFIX.length());
    if (!uri.startsWith(PREFIX)
        || slash < 0
        || !(uri.startsWith(HARNESS, slash) || uri.startsWith("/web", slash))) {
      throw notFound();
    }
    return target(request, uri, slash, boundVmId);
  }

  private static HarnessTarget target(
      HttpServletRequest request, String uri, int slash, String vmIdValue) {
    UUID vmId;
    try {
      vmId = UUID.fromString(vmIdValue);
    } catch (IllegalArgumentException _) {
      throw notFound();
    }
    var web = uri.startsWith("/web", slash);
    var path = uri.substring(slash + (web ? 4 : HARNESS.length()));
    if (!path.isEmpty() && !path.startsWith("/")) {
      throw notFound();
    }
    var query = request.getQueryString();
    return new HarnessTarget(
        vmId, (path.isEmpty() ? "/" : path) + (query == null ? "" : "?" + query), web);
  }

  private static TunnelException notFound() {
    return new TunnelException(HttpStatus.NOT_FOUND, "not a VM's harness");
  }
}
