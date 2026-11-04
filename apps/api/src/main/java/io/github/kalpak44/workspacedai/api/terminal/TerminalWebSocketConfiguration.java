package io.github.kalpak44.workspacedai.api.terminal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** The browser terminal is a byte stream, so it uses a WebSocket rather than an HTTP endpoint. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class TerminalWebSocketConfiguration implements WebSocketConfigurer {

  private final TerminalWebSocketHandler handler;

  public TerminalWebSocketConfiguration(TerminalWebSocketHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, "/workspaces/*/terminals/*/connect");
  }
}
