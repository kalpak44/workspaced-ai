package io.github.kalpak44.workspacedai.api.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionRepository;
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
import java.util.function.Supplier;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every route needs a signed-in user except signing in, health and the API docs. A user reaches a
 * workspace, and everything under it (sessions, secrets, settings, terminals), only if they own it
 * or were invited to it; anyone else is told it does not exist, so ids cannot be probed.
 *
 * <p>Auth0 signs users in (see {@link Auth0SignIn}); the token is a JWT the API then signs itself.
 * Browsers carry it in an HttpOnly cookie, because EventSource and WebSocket cannot set an
 * Authorization header; other clients send it as a bearer token.
 *
 * <p>Users run what they like on VMs named under the same site, so SameSite alone protects nothing
 * here. Every cookie is __Host-, which a sibling origin cannot set or shadow; a change must come
 * from this origin; and it must carry the CSRF token in X-XSRF-TOKEN, as GET /auth/csrf hands it
 * out, checked against the HttpOnly __Host-XSRF-TOKEN cookie. A bearer request needs neither, since
 * a browser never adds that header on its own.
 *
 * <p>Every request also looks the account up: a token outlives a deleted account otherwise, and the
 * role is read from there.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {

  static final String COOKIE = "__Host-workspaced_token";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  // The conversion Spring MVC binds a UUID path variable with, which trims surrounding whitespace.
  private static final ConversionService CONVERSIONS = DefaultConversionService.getSharedInstance();
  private static final int MIN_SECRET_BYTES = 32;

  private static final String[] PUBLIC = {
    Auth0SignIn.AUTHORIZATION + "/*",
    Auth0SignIn.CALLBACK,
    // An unverified sign-up has no account; the short-lived cookie it carries is its proof.
    "/auth/verification-email",
    "/auth/logout",
    "/auth/end-session",
    "/auth/csrf",
    "/actuator/health/**",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html",
    // A download link carries its own signed token, and is opened outside the app.
    "/files/*",
    "/error"
  };

  private static final String[] ADMIN_ONLY = {"/mail", "/mail/**", "/admin/**"};

  /**
   * Stripe's webhook is not a browser route: it carries no cookie, and the signature on each event
   * is its credential. It runs outside the security chain so the browser routes keep CSRF
   * protection with no exception; the webhook verifies the signature before acting.
   */
  @Bean
  WebSecurityCustomizer stripeWebhook() {
    return web -> web.ignoring().requestMatchers("/billing/stripe/webhook");
  }

  @Bean
  SecurityFilterChain api(
      HttpSecurity http,
      WorkspaceRepository workspaces,
      WorkspaceSessionRepository sessions,
      UserRepository users,
      ClientRegistrationRepository registrations,
      AuthService auth,
      AuthProperties properties)
      throws Exception {
    var publicPaths = publicPaths();
    var adminOnly = matcher(ADMIN_ONLY);
    http.csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfCookie())
                    .csrfTokenRequestHandler(new HeaderOnlyCsrfTokens()))
        .addFilterBefore(new SameOriginChanges(), CsrfFilter.class)
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
                    .requestMatchers(
                        "/workspaces/{workspaceId}/sessions/{sessionId}",
                        "/workspaces/{workspaceId}/sessions/{sessionId}/**")
                    .access(ownSession(workspaces, sessions))
                    .requestMatchers("/workspaces/{workspaceId}", "/workspaces/{workspaceId}/**")
                    .access(ownedWorkspace(workspaces))
                    .requestMatchers(adminOnly)
                    .hasRole(Role.ADMIN.name())
                    .anyRequest()
                    .authenticated())
        // The login page is Auth0's, so Spring's generated one is never served.
        .oauth2Login(
            login ->
                login
                    .loginPage(Auth0SignIn.AUTHORIZATION + "/" + Auth0SignIn.REGISTRATION)
                    // In a cookie, so the callback can reach any instance.
                    .authorizationEndpoint(
                        a ->
                            a.authorizationRequestResolver(
                                    Auth0SignIn.authorizationRequests(registrations))
                                .authorizationRequestRepository(
                                    new CookieAuthorizationRequests(properties.jwtSecret(), JSON)))
                    .authorizedClientRepository(new NoAuthorizedClients())
                    .successHandler(Auth0SignIn.signedIn(auth, properties))
                    .failureHandler(Auth0SignIn.failed()))
        .oauth2ResourceServer(
            server ->
                server
                    .bearerTokenResolver(headerResolver(publicPaths))
                    .authenticationEntryPoint(SecurityConfiguration::unauthorized)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(currentAccount(users))))
        .addFilterBefore(new CookieToken(publicPaths), BearerTokenAuthenticationFilter.class)
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(SecurityConfiguration::unauthorized)
                    .accessDeniedHandler(
                        (request, response, denied) ->
                            denied(request, response, denied, adminOnly)));
    return http.build();
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
    repository.setCookieName("__Host-XSRF-TOKEN");
    repository.setCookiePath("/");
    repository.setCookieCustomizer(cookie -> cookie.sameSite("Strict").secure(true));
    return repository;
  }

  /**
   * Spring would keep each sign-in's Auth0 tokens, in memory, to call Auth0 as the user. The API
   * never does, so they would only hold live credentials and grow with every user.
   */
  private static final class NoAuthorizedClients implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
        String registrationId, Authentication principal, HttpServletRequest request) {
      return null;
    }

    @Override
    public void saveAuthorizedClient(
        OAuth2AuthorizedClient client,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response) {
      // Kept nowhere.
    }

    @Override
    public void removeAuthorizedClient(
        String registrationId,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response) {
      // Nothing was kept.
    }
  }

  /**
   * The token from the header alone. Spring also reads a form field, which a plain HTML form on
   * another origin can send; the header needs script running on this one.
   */
  private static final class HeaderOnlyCsrfTokens implements CsrfTokenRequestHandler {

    private final XorCsrfTokenRequestAttributeHandler masked =
        new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
        HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
      masked.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
      return request.getHeader(csrfToken.getHeaderName()) == null
          ? null
          : masked.resolveCsrfTokenValue(request, csrfToken);
    }
  }

  /**
   * Refuses a change a browser sent from any other origin, a user's VM under the same site
   * included. Browsers say where a request comes from in Sec-Fetch-Site, and older ones in Origin;
   * a request with neither, or with an Authorization header, comes from no browser page.
   */
  private static final class SameOriginChanges extends OncePerRequestFilter {

    private static final java.util.Set<String> SAFE = java.util.Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      if (SAFE.contains(request.getMethod())
          || request.getHeader(HttpHeaders.AUTHORIZATION) != null
          || sameOrigin(request)) {
        chain.doFilter(request, response);
        return;
      }
      problem(request, response, HttpStatus.FORBIDDEN, "changes are accepted from this site only");
    }

    private static boolean sameOrigin(HttpServletRequest request) {
      var site = request.getHeader("Sec-Fetch-Site");
      if (site != null) {
        return "same-origin".equals(site) || "none".equals(site);
      }
      var origin = request.getHeader(HttpHeaders.ORIGIN);
      if (origin == null) {
        return true;
      }
      var own = request.getScheme() + "://" + request.getHeader(HttpHeaders.HOST);
      return origin.equalsIgnoreCase(own);
    }
  }

  private static RequestMatcher publicPaths() {
    return matcher(PUBLIC);
  }

  private static RequestMatcher matcher(String... patterns) {
    var matchers = PathPatternRequestMatcher.withDefaults();
    return new OrRequestMatcher(Arrays.stream(patterns).map(matchers::matcher).toList());
  }

  /**
   * Turns away the token of an account deleted since it was issued, or of a session its user signed
   * out of, and grants the role the account has now, so a role Auth0 changed applies from the next
   * sign-in on.
   */
  private static Converter<Jwt, AbstractAuthenticationToken> currentAccount(UserRepository users) {
    return jwt -> {
      // A token issued for one purpose, such as asking for the verification email again, is no
      // sign-in.
      if (jwt.hasClaim(CurrentUser.USE)) {
        throw new InvalidBearerTokenException("the token signs no one in");
      }
      var access =
          users
              .findAccessById(UUID.fromString(jwt.getSubject()))
              .orElseThrow(() -> new InvalidBearerTokenException("the account no longer exists"));
      if (jwt.getIssuedAt() == null || jwt.getIssuedAt().isBefore(access.getSessionsValidAfter())) {
        throw new InvalidBearerTokenException("the session was signed out");
      }
      return new JwtAuthenticationToken(
          jwt, List.of(new SimpleGrantedAuthority(access.getRole().authority())), jwt.getSubject());
    };
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
   * Allows a workspace route only to its owner and its collaborators. An id that is not a UUID, or
   * that no workspace has, is let through for the controller to answer 400 or 404 as it always has.
   * The id is parsed the way the controller binds it: a stricter parse here would let " <uuid>"
   * through as "not a UUID" while the controller trims it and acts on that workspace.
   */
  private static AuthorizationManager<RequestAuthorizationContext> ownedWorkspace(
      WorkspaceRepository workspaces) {
    return (authentication, context) ->
        new AuthorizationDecision(
            owns(authentication.get(), context.getVariables().get("workspaceId"), workspaces));
  }

  /**
   * Allows a session's routes only to the user who started it, or, for a scheduled task's run, to
   * anyone on the workspace, so collaborators never read one another's chats. An id that is not a
   * UUID, or that no session there has, is let through for the controller to answer as before.
   */
  private static AuthorizationManager<RequestAuthorizationContext> ownSession(
      WorkspaceRepository workspaces, WorkspaceSessionRepository sessions) {
    return (authentication, context) -> {
      var variables = context.getVariables();
      var user = authentication.get();
      if (!owns(user, variables.get("workspaceId"), workspaces)) {
        return new AuthorizationDecision(false);
      }
      UUID workspace;
      UUID session;
      try {
        workspace = CONVERSIONS.convert(variables.get("workspaceId"), UUID.class);
        session = CONVERSIONS.convert(variables.get("sessionId"), UUID.class);
      } catch (ConversionException _) {
        return new AuthorizationDecision(true);
      }
      if (workspace == null || session == null) {
        return new AuthorizationDecision(true);
      }
      var subject = ((JwtAuthenticationToken) user).getToken().getSubject();
      return new AuthorizationDecision(
          sessions.findVisibility(workspace, session, UUID.fromString(subject)).orElse(true));
    };
  }

  private static boolean owns(
      Authentication authentication, String workspaceId, WorkspaceRepository workspaces) {
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      return false;
    }
    UUID id;
    try {
      id = CONVERSIONS.convert(workspaceId, UUID.class);
    } catch (ConversionException _) {
      return true;
    }
    if (id == null) {
      return false;
    }
    var user = UUID.fromString(token.getToken().getSubject());
    return workspaces.findAccess(id, user).orElse(true);
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
   * A missing CSRF token is the client's to fix, so it says so, as does an admin-only route. Any
   * other denial is a workspace the user may not reach, which answers as one that does not exist.
   */
  private static void denied(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException denied,
      RequestMatcher adminOnly)
      throws IOException {
    if (denied instanceof CsrfException) {
      problem(request, response, HttpStatus.FORBIDDEN, "missing or invalid CSRF token");
    } else if (adminOnly.matches(request)) {
      problem(request, response, HttpStatus.FORBIDDEN, "only an admin can do that");
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
