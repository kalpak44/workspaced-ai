package io.github.kalpak44.workspacedai.agenthub.agent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers 404 to an internal route reached through the ingress, in case a route there ever forwards
 * more than the agent prefix.
 */
@Component
public class InternalRoutesFilter extends OncePerRequestFilter {

  // Traefik adds these to every request it forwards, and in-cluster callers never send them.
  private static final List<String> PROXY_HEADERS =
      List.of("X-Forwarded-For", "X-Forwarded-Host", "X-Real-Ip");

  private final JsonMapper json;

  public InternalRoutesFilter(JsonMapper json) {
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // The servlet path is decoded and normalised, so /agent/../internal cannot slip past.
    var path =
        request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
    return !(path.equals("/internal") || path.startsWith("/internal/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (PROXY_HEADERS.stream().noneMatch(header -> request.getHeader(header) != null)) {
      chain.doFilter(request, response);
      return;
    }
    var problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setInstance(URI.create(request.getRequestURI()));
    response.setStatus(HttpStatus.NOT_FOUND.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), problem);
  }
}
