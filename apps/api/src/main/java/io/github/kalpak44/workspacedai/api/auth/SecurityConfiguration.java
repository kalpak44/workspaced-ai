package io.github.kalpak44.workspacedai.api.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every route needs a signed-in user except signing in, health and the API docs. A user reaches a
 * workspace, and everything under it (sessions, secrets, settings, terminals), only if they own it;
 * anyone else is told it does not exist, so ids cannot be probed.
 *
 * <p>The token is a JWT the API signs itself. Browsers carry it in an HttpOnly cookie, because
 * EventSource and WebSocket cannot set an Authorization header; other clients send it as a bearer
 * token. The cookie is SameSite=Strict, but that does not cover a sibling subdomain on the same
 * site, so a change sent with that cookie also needs the CSRF token: the page reads it from GET
 * /auth/csrf and echoes it in X-XSRF-TOKEN, and it is checked against the HttpOnly XSRF-TOKEN
 * cookie. A request without the sign-in cookie has no ambient credential to forge, and a bearer
 * request needs none, since a browser never adds that header on its own.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {

  static final String COOKIE = "workspaced_token";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MIN_SECRET_BYTES = 32;

  private static final String[] PUBLIC = {
    "/auth/login",
    "/auth/logout",
    "/auth/csrf",
    "/actuator/health/**",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html",
    "/error"
  };

  @Bean
  SecurityFilterChain api(HttpSecurity http, WorkspaceRepository workspaces) throws Exception {
    var publicPaths = publicPaths();
    http.csrf(csrf -> csrf.csrfTokenRepository(csrfCookie()))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    // A streaming response and an error page are later dispatches of a request
                    // that was already authorized.
                    .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(publicPaths)
                    .permitAll()
                    .requestMatchers("/workspaces/events")
                    .authenticated()
                    .requestMatchers("/workspaces/{workspaceId}", "/workspaces/{workspaceId}/**")
                    .access(ownedWorkspace(workspaces))
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            server ->
                server
                    .bearerTokenResolver(headerResolver(publicPaths))
                    .authenticationEntryPoint(SecurityConfiguration::unauthorized)
                    .jwt(_ -> {}))
        .addFilterBefore(new CookieToken(publicPaths), BearerTokenAuthenticationFilter.class)
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(SecurityConfiguration::unauthorized)
                    .accessDeniedHandler(SecurityConfiguration::denied));
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(UserRepository users) {
    return username ->
        users
            .findByUsername(username)
            .map(u -> User.withUsername(u.getUsername()).password(u.getPasswordHash()).build())
            .orElseThrow(() -> new UsernameNotFoundException("no user " + username));
  }

  @Bean
  AuthenticationManager authenticationManager(
      UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    var provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(provider);
  }

  @Bean
  SecretKey jwtKey(AuthProperties properties) {
    var secret = properties.jwtSecret();
    var configured = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    if (configured.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "WORKSPACED_JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
    }
    return new SecretKeySpec(configured, "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey jwtKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
  }

  @Bean
  JwtDecoder jwtDecoder(SecretKey jwtKey) {
    var decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenService.ISSUER));
    return decoder;
  }

  /** HttpOnly like the sign-in cookie; the page learns the token from GET /auth/csrf instead. */
  private static CookieCsrfTokenRepository csrfCookie() {
    var repository = new CookieCsrfTokenRepository();
    repository.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));
    return repository;
  }

  private static RequestMatcher publicPaths() {
    var matchers = PathPatternRequestMatcher.withDefaults();
    return new OrRequestMatcher(Arrays.stream(PUBLIC).map(matchers::matcher).toList());
  }

  /**
   * The Authorization header only. A public route ignores it, so a stale token cannot turn signing
   * in, or the health check, into a 401. The resource server also exempts from CSRF every request
   * this resolves a token for, which is why the cookie is not read here but by {@link CookieToken}.
   */
  private static BearerTokenResolver headerResolver(RequestMatcher publicPaths) {
    var header = new DefaultBearerTokenResolver();
    return request -> publicPaths.matches(request) ? null : header.resolve(request);
  }

  /**
   * Presents the cookie's token as a bearer header. It runs after the CSRF check, so a request that
   * authenticates by cookie has had to pass it first.
   */
  private static final class CookieToken extends OncePerRequestFilter {

    private final RequestMatcher publicPaths;

    private CookieToken(RequestMatcher publicPaths) {
      this.publicPaths = publicPaths;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      var token = cookie(request);
      if (token == null
          || request.getHeader(HttpHeaders.AUTHORIZATION) != null
          || publicPaths.matches(request)) {
        chain.doFilter(request, response);
        return;
      }
      chain.doFilter(
          new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
              return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                  ? "Bearer " + token
                  : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
              return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                  ? Collections.enumeration(List.of("Bearer " + token))
                  : super.getHeaders(name);
            }
          },
          response);
    }

    private static String cookie(HttpServletRequest request) {
      if (request.getCookies() == null) {
        return null;
      }
      return Arrays.stream(request.getCookies())
          .filter(c -> COOKIE.equals(c.getName()) && !c.getValue().isBlank())
          .map(Cookie::getValue)
          .findFirst()
          .orElse(null);
    }
  }

  /**
   * Allows a workspace route only to its owner. An id that is not a UUID, or that no workspace has,
   * is let through for the controller to answer 400 or 404 as it always has.
   */
  private static AuthorizationManager<RequestAuthorizationContext> ownedWorkspace(
      WorkspaceRepository workspaces) {
    return (authentication, context) ->
        new AuthorizationDecision(
            owns(authentication.get(), context.getVariables().get("workspaceId"), workspaces));
  }

  private static boolean owns(
      Authentication authentication, String workspaceId, WorkspaceRepository workspaces) {
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      return false;
    }
    UUID id;
    try {
      id = UUID.fromString(workspaceId);
    } catch (IllegalArgumentException _) {
      return true;
    }
    var user = UUID.fromString(token.getToken().getSubject());
    return workspaces.findOwnerIdById(id).map(user::equals).orElse(true);
  }

  private static void unauthorized(
      HttpServletRequest request,
      HttpServletResponse response,
      org.springframework.security.core.AuthenticationException e)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    problem(request, response, HttpStatus.UNAUTHORIZED, "sign in first");
  }

  /**
   * A missing CSRF token is the client's to fix, so it says so. Any other denial is a workspace the
   * user does not own, which answers as one that does not exist.
   */
  private static void denied(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
      throws IOException {
    if (denied instanceof CsrfException) {
      problem(request, response, HttpStatus.FORBIDDEN, "missing or invalid CSRF token");
    } else {
      problem(request, response, HttpStatus.NOT_FOUND, "no such workspace");
    }
  }

  /** The RFC 9457 shape every other error has, written here because no controller is involved. */
  private static void problem(
      HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
      throws IOException {
    var body = new LinkedHashMap<String, Object>();
    body.put("type", "about:blank");
    body.put("title", status.getReasonPhrase());
    body.put("status", status.value());
    body.put("detail", detail);
    body.put("instance", URI.create(request.getRequestURI()).toString());
    response.setStatus(status.value());
    response.setContentType("application/problem+json");
    response.getOutputStream().write(JSON.writeValueAsBytes(body));
  }
}
