package io.github.kalpak44.workspacedai.agenthub.agent.web;

import io.github.kalpak44.workspacedai.agenthub.caller.CallerProperties;
import io.github.kalpak44.workspacedai.agenthub.caller.CallerVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards every internal route. Each caller proves which service account it is with a projected
 * token in {@link #TOKEN_HEADER}, and reaches only its own routes: the API a VM's harness, the
 * gateway a VM's public web server, the VM manager enrollments. A route reached through the ingress
 * is refused before any of that.
 */
@Component
public class InternalRoutesFilter extends OncePerRequestFilter {

  /** Never passed on to a VM, which could otherwise replay it here. */
  public static final String TOKEN_HEADER = "X-Agent-Hub-Token";

  // Traefik adds these to every request it forwards, and in-cluster callers never send them.
  private static final List<String> PROXY_HEADERS =
      List.of("X-Forwarded-For", "X-Forwarded-Host", "X-Real-Ip");

  private static final Pattern HARNESS = Pattern.compile("^/internal/vms/[^/]+/harness(/.*)?$");
  private static final Pattern WEB = Pattern.compile("^/internal/vms/[^/]+/web(/.*)?$");
  private static final Pattern ENROLLMENTS =
      Pattern.compile("^/internal/(enrollments|agents/[^/]+)$");

  private final JsonMapper json;
  private final CallerVerifier callers;
  private final CallerProperties properties;

  public InternalRoutesFilter(
      JsonMapper json, CallerVerifier callers, CallerProperties properties) {
    this.json = json;
    this.callers = callers;
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // The servlet path is decoded and normalised, so /agent/../internal cannot slip past.
    var path =
        request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
    return !(internal(path) || internal(raw(request)));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var raw = raw(request);
    if (PROXY_HEADERS.stream().anyMatch(header -> request.getHeader(header) != null)
        || !canonical(raw)) {
      refuse(request, response, HttpStatus.NOT_FOUND, null);
      return;
    }
    var owner = owner(raw);
    if (owner.isEmpty()) {
      refuse(request, response, HttpStatus.NOT_FOUND, null);
      return;
    }
    var token = request.getHeader(TOKEN_HEADER);
    var caller =
        token == null || token.isBlank() ? Optional.<String>empty() : callers.verify(token);
    if (caller.isEmpty()) {
      refuse(request, response, HttpStatus.UNAUTHORIZED, "a caller token is required");
    } else if (!caller.get().equals(owner.get())) {
      refuse(request, response, HttpStatus.FORBIDDEN, "this caller may not use this route");
    } else {
      chain.doFilter(request, response);
    }
  }

  /**
   * The service account a route belongs to, read from the raw URI the way the routes themselves
   * read it, so the route authorized is the route served.
   */
  private Optional<String> owner(String raw) {
    if (HARNESS.matcher(raw).matches()) {
      return Optional.of(properties.api());
    }
    if (WEB.matcher(raw).matches()) {
      return Optional.of(properties.gateway());
    }
    if (ENROLLMENTS.matcher(raw).matches()) {
      return Optional.of(properties.vmManager());
    }
    return Optional.empty();
  }

  /**
   * Refuses anything the servlet container would normalise differently from the raw URI: path
   * parameters, dot segments, empty segments and encoded dots or slashes. Otherwise a web route
   * could be authorized while a harness route is served, or the other way round.
   */
  private static boolean canonical(String raw) {
    var lower = raw.toLowerCase(Locale.ROOT);
    return !(lower.contains(";")
        || lower.contains("\\")
        || lower.contains("//")
        || lower.contains("%2e")
        || lower.contains("%2f")
        || lower.contains("%5c")
        || lower.contains("/./")
        || lower.contains("/../")
        || lower.endsWith("/.")
        || lower.endsWith("/.."));
  }

  private static boolean internal(String path) {
    return path.equals("/internal") || path.startsWith("/internal/");
  }

  private static String raw(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  private void refuse(
      HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
      throws IOException {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setInstance(URI.create(request.getRequestURI()));
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), problem);
  }
}
