package io.github.kalpak44.workspacedai.api.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every route needs a signed-in user except signing in, health and the API docs. A user reaches a
 * workspace, and everything under it (sessions, secrets, settings, terminals), only if they own it;
 * anyone else is told it does not exist, so ids cannot be probed.
 *
 * <p>The token is a JWT the API signs itself. Browsers carry it in an HttpOnly cookie, because
 * EventSource and WebSocket cannot set an Authorization header; other clients send it as a bearer
 * token. The cookie is SameSite=Strict, so another site cannot make the browser send it, which is
 * what CSRF protection would otherwise be for.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {

  static final String COOKIE = "workspaced_token";

  private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MIN_SECRET_BYTES = 32;

  private static final String[] PUBLIC = {
    "/auth/login",
    "/auth/logout",
    "/actuator/health/**",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html",
    "/error"
  };

  @Bean
  SecurityFilterChain api(HttpSecurity http, WorkspaceRepository workspaces) throws Exception {
    var publicPaths = publicPaths();
    http.csrf(AbstractHttpConfigurer::disable)
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
                    .bearerTokenResolver(tokenResolver(publicPaths))
                    .authenticationEntryPoint(SecurityConfiguration::unauthorized)
                    .jwt(jwt -> {}))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(SecurityConfiguration::unauthorized)
                    .accessDeniedHandler(
                        (request, response, denied) ->
                            problem(request, response, HttpStatus.NOT_FOUND, "no such workspace")));
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
    var configured = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
    if (configured.length == 0) {
      LOG.warn(
          "WORKSPACED_JWT_SECRET is not set: signing tokens with a random key, so every restart "
              + "signs everyone out");
      configured = new byte[MIN_SECRET_BYTES];
      new SecureRandom().nextBytes(configured);
    } else if (configured.length < MIN_SECRET_BYTES) {
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

  private static RequestMatcher publicPaths() {
    var matchers = PathPatternRequestMatcher.withDefaults();
    return new OrRequestMatcher(Arrays.stream(PUBLIC).map(matchers::matcher).toList());
  }

  /**
   * The header first, then the cookie. A public route ignores both, so a stale cookie cannot turn
   * signing in, or the health check, into a 401.
   */
  private static BearerTokenResolver tokenResolver(RequestMatcher publicPaths) {
    var header = new DefaultBearerTokenResolver();
    return request -> {
      if (publicPaths.matches(request)) {
        return null;
      }
      var token = header.resolve(request);
      if (token != null || request.getCookies() == null) {
        return token;
      }
      return Arrays.stream(request.getCookies())
          .filter(c -> COOKIE.equals(c.getName()) && !c.getValue().isBlank())
          .map(jakarta.servlet.http.Cookie::getValue)
          .findFirst()
          .orElse(null);
    };
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
