package io.github.kalpak44.workspacedai.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Signing in through Auth0. The browser goes to its hosted login and comes back to the callback,
 * where the API issues its own token as the sign-in cookie and sends the browser on to the app.
 * Nothing is kept on the server in between, so any instance can take the callback: see {@link
 * CookieAuthorizationRequests}.
 */
@Configuration(proxyBeanMethods = false)
class Auth0SignIn {

  static final String REGISTRATION = "auth0";
  static final String AUTHORIZATION = "/oauth2/authorization";
  static final String CALLBACK = "/login/oauth2/code/*";

  // Auth0 only passes a custom claim through when it is namespaced to a URL of our own. The
  // tenant's post-login Action sets these: the roles, and the names the sign-up form saved.
  static final String ROLES_CLAIM = "https://workspaced.online/roles";
  static final String GIVEN_NAME_CLAIM = "https://workspaced.online/given_name";
  static final String FAMILY_NAME_CLAIM = "https://workspaced.online/family_name";
  static final String ADMIN_ROLE = "admin";

  // Asks for MFA; the tenant's post-login Action enrolls a user who has not set it up yet.
  private static final String MFA = "http://schemas.openid.net/pape/policies/2007/06/multi-factor";

  /**
   * Built here rather than discovered, so starting the API makes no call to Auth0. The issuer is
   * still checked on every ID token, and the profile comes from that token, so no user-info call.
   */
  @Bean
  ClientRegistrationRepository clientRegistrations(AuthProperties properties) {
    var issuer = properties.issuer();
    return new InMemoryClientRegistrationRepository(
        ClientRegistration.withRegistrationId(REGISTRATION)
            .clientId(properties.clientId())
            .clientSecret(properties.clientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "email", "profile")
            .issuerUri(issuer)
            .authorizationUri(issuer + "authorize")
            .tokenUri(issuer + "oauth/token")
            .jwkSetUri(issuer + ".well-known/jwks.json")
            .userNameAttributeName(IdTokenClaimNames.SUB)
            .build());
  }

  /**
   * The standard request, plus the MFA ask when the app sends the user here with mfa=enroll, and
   * the sign-up form with signup.
   */
  static OAuth2AuthorizationRequestResolver authorizationRequests(
      ClientRegistrationRepository registrations) {
    var standard = new DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION);
    return new OAuth2AuthorizationRequestResolver() {
      @Override
      public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withHints(request, standard.resolve(request));
      }

      @Override
      public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return withHints(request, standard.resolve(request, registrationId));
      }
    };
  }

  private static OAuth2AuthorizationRequest withHints(
      HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
    if (authorization == null) {
      return null;
    }
    var mfa = "enroll".equals(request.getParameter("mfa"));
    var signUp = request.getParameter("signup") != null;
    var attribution =
        Attribution.of(
            request.getParameterMap().entrySet().stream()
                .filter(e -> e.getValue().length > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue()[0])));
    if (!mfa && !signUp && attribution.isEmpty()) {
      return authorization;
    }
    return OAuth2AuthorizationRequest.from(authorization)
        // Kept in the encrypted sign-in cookie, never sent to Auth0.
        .attributes(
            attributes -> {
              if (attribution.source() != null) {
                attributes.put(Attribution.SOURCE_PARAM, attribution.source());
              }
              if (attribution.campaign() != null) {
                attributes.put(Attribution.CAMPAIGN_PARAM, attribution.campaign());
              }
              if (attribution.referral() != null) {
                attributes.put(Attribution.REFERRAL_PARAM, attribution.referral());
              }
            })
        .additionalParameters(
            parameters -> {
              if (mfa) {
                parameters.put("acr_values", MFA);
              }
              // Opens Auth0's page on its sign-up form rather than the sign-in one.
              if (signUp) {
                parameters.put("screen_hint", "signup");
              }
            })
        .build();
  }

  /**
   * Sends the browser to the app signed in, and greets a new account there and by mail. An email
   * Auth0 has not verified is refused, since the app mails it: the Auth0 session is ended, so the
   * next sign-in asks again, and the browser lands on the app's page that explains it, carrying
   * what it needs to ask for the email again.
   */
  static AuthenticationSuccessHandler signedIn(AuthService auth, AuthProperties properties) {
    return (request, response, authentication) -> {
      var user = (OidcUser) authentication.getPrincipal();
      var pending =
          auth.verificationAttribution(
              user.getSubject(), AuthController.verificationCookie(request));
      // Keep the original invitation for this identity through repeated verification attempts.
      if (!pending.isEmpty()) request.setAttribute(Attribution.REQUEST_ATTRIBUTE, pending);
      AuthService.SignIn signIn;
      try {
        signIn = signIn(auth, user, request);
      } catch (AuthService.UnverifiedEmailException _) {
        var proof = auth.verificationToken(user.getSubject(), attribution(request));
        response.addHeader(
            HttpHeaders.SET_COOKIE, AuthController.verificationCookie(proof).toString());
        response.sendRedirect(logoutUrl(properties, properties.appUrl() + "/verify-email"));
        return;
      }
      response.addHeader(HttpHeaders.SET_COOKIE, AuthController.cookie(signIn.token()).toString());
      response.addHeader(
          HttpHeaders.SET_COOKIE, AuthController.clearVerificationCookie().toString());
      if (!signIn.created()) {
        response.sendRedirect("/");
        return;
      }
      auth.sendWelcomeMail(signIn.email());
      response.sendRedirect("/?welcome");
    };
  }

  /**
   * A first sign-in finished twice at once, in two tabs, makes the account twice; the loser finds
   * the winner's account on a second try.
   */
  private static AuthService.SignIn signIn(
      AuthService auth, OidcUser user, HttpServletRequest request) {
    var profile =
        new Auth0Users.Profile(
            text(user.getEmail()),
            text(user.getClaimAsString(GIVEN_NAME_CLAIM)),
            text(user.getClaimAsString(FAMILY_NAME_CLAIM)),
            text(user.getPicture()));
    var verified = Boolean.TRUE.equals(user.getEmailVerified());
    try {
      return auth.signIn(user.getSubject(), profile, verified, role(user), attribution(request));
    } catch (DataIntegrityViolationException _) {
      return auth.signIn(user.getSubject(), profile, verified, role(user), attribution(request));
    }
  }

  /** What the sign-in cookie carried from the link that started this sign-in. */
  private static Attribution attribution(HttpServletRequest request) {
    return request.getAttribute(Attribution.REQUEST_ATTRIBUTE) instanceof Attribution kept
        ? kept
        : Attribution.NONE;
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  /** Auth0's logout, which ends its session and sends the browser on to an allowed address. */
  static String logoutUrl(AuthProperties properties, String returnTo) {
    return properties.issuer()
        + "v2/logout?client_id="
        + URLEncoder.encode(properties.clientId(), StandardCharsets.UTF_8)
        + "&returnTo="
        + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
  }

  /** The roles Auth0 assigned the user, which the tenant's post-login Action puts in this claim. */
  static Role role(OidcUser user) {
    var roles = user.getClaimAsStringList(ROLES_CLAIM);
    return roles != null && roles.contains(ADMIN_ROLE) ? Role.ADMIN : Role.USER;
  }

  /** Auth0 turned the user away, or the callback did not check out. */
  static AuthenticationFailureHandler failed() {
    return (request, response, exception) -> response.sendRedirect("/login?error=failed");
  }
}
