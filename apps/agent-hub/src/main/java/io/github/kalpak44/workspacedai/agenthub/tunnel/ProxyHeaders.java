package io.github.kalpak44.workspacedai.agenthub.tunnel;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Which headers cross the tunnel: only end-to-end ones, never the API's credentials. */
final class ProxyHeaders {

  // Each describes one hop's connection, which the tunnel replaces with hops of its own.
  private static final Set<String> HOP_BY_HOP =
      Set.of(
          "connection",
          "keep-alive",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          "host",
          "expect");

  // The supervisor dials the harness's socket itself, with a key and version of its own.
  private static final Set<String> HANDSHAKE =
      Set.of(
          "sec-websocket-key",
          "sec-websocket-version",
          "sec-websocket-extensions",
          "sec-websocket-accept");

  private ProxyHeaders() {}

  /** An HTTP call's headers, for its head. */
  static Map<String, List<String>> ofRequest(HttpServletRequest request) {
    return of(request, false);
  }

  /** A socket handshake's headers, for its head; Sec-WebSocket-Protocol is the one kept. */
  static Map<String, List<String>> ofHandshake(HttpServletRequest request) {
    return of(request, true);
  }

  private static Map<String, List<String>> of(HttpServletRequest request, boolean handshake) {
    var headers = new LinkedHashMap<String, List<String>>();
    for (var name : Collections.list(request.getHeaderNames())) {
      var lower = name.toLowerCase(Locale.ROOT);
      // The harness takes the supervisor's password, so the API's never needs to leave the hub.
      if (dropped(lower)
          || lower.equals("authorization")
          || handshake && HANDSHAKE.contains(lower)) {
        continue;
      }
      headers.put(name, Collections.list(request.getHeaders(name)));
    }
    return headers;
  }

  /** Whether a response header from the harness stays behind. */
  static boolean dropped(String name) {
    var lower = name.toLowerCase(Locale.ROOT);
    return HOP_BY_HOP.contains(lower) || lower.startsWith("proxy-");
  }
}
