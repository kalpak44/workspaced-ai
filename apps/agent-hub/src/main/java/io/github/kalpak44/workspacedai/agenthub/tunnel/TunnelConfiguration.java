package io.github.kalpak44.workspacedai.agenthub.tunnel;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

/**
 * Maps the two socket endpoints. The mapping matches socket upgrades alone and runs before the
 * controllers, so a plain request to a harness path falls through to {@link HarnessProxyController}
 * on the very same path.
 */
@Configuration(proxyBeanMethods = false)
public class TunnelConfiguration {

  @Bean
  WebSocketHandlerMapping tunnelHandlerMapping(
      WorkerSocketHandler workers, HarnessSocketHandler harness) {
    var handshake = new RelayingHandshakeHandler();
    var mapping = new WebSocketHandlerMapping();
    mapping.setUrlMap(
        Map.of(
            "/agent/tunnel", endpoint(workers, workers, handshake),
            "/internal/vms/*/harness/**", endpoint(harness, harness, handshake)));
    mapping.setWebSocketUpgradeMatch(true);
    mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return mapping;
  }

  private static WebSocketHttpRequestHandler endpoint(
      WebSocketHandler handler, HandshakeInterceptor interceptor, HandshakeHandler handshake) {
    var endpoint = new WebSocketHttpRequestHandler(handler, handshake);
    endpoint.setHandshakeInterceptors(List.of(interceptor));
    return endpoint;
  }

  /** Answers the API with the subprotocol the harness chose, when the API offered it. */
  static final class RelayingHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected String selectProtocol(List<String> requested, WebSocketHandler handler) {
      var request = RequestContextHolder.getRequestAttributes();
      var chosen =
          request == null
              ? null
              : request.getAttribute(
                  HarnessSocketHandler.PROTOCOL, RequestAttributes.SCOPE_REQUEST);
      return chosen instanceof String protocol && requested.contains(protocol) ? protocol : null;
    }
  }
}
