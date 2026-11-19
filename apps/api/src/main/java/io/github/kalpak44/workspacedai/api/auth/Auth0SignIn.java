package io.github.kalpak44.workspacedai.api.auth;

import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Signing in through Auth0. The browser goes to its hosted login and comes back to the callback,
 * where the API issues its own token as the sign-in cookie and sends the browser on to the app. The
 * authorization request in flight is the only thing kept in a session, and only for that round
 * trip.
 */
@Configuration(proxyBeanMethods = false)
class Auth0SignIn {

  static final String REGISTRATION = "auth0";
  static final String AUTHORIZATION = "/oauth2/authorization";
  static final String CALLBACK = "/login/oauth2/code/*";

  // Asks for MFA; the tenant's post-login Action enrolls a user who has not set it up yet.
  private static final String MFA = "http://schemas.openid.net/pape/policies/2007/06/multi-factor";

  /**
   * Built here rather than discovered, so starting the API makes no call to Auth0. The issuer is
   * still checked on every ID token, and the email comes from that token, so no user-info call.
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
            .scope("openid", "email")
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
    if (!mfa && !signUp) {
      return authorization;
    }
    return OAuth2AuthorizationRequest.from(authorization)
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
   * Auth0 has not verified is refused, since the account is matched by it; so is one another
   * account already holds.
   */
  static AuthenticationSuccessHandler signedIn(AuthService auth) {
    return (request, response, authentication) -> {
      endRoundTrip(request);
      var user = (OidcUser) authentication.getPrincipal();
      AuthService.SignIn signIn;
      try {
        signIn =
            auth.signIn(
                user.getSubject(), user.getEmail(), Boolean.TRUE.equals(user.getEmailVerified()));
      } catch (AuthService.UnverifiedEmailException _) {
        response.sendRedirect("/login?error=unverified");
        return;
      } catch (DataIntegrityViolationException _) {
        response.sendRedirect("/login?error=failed");
        return;
      }
      response.addHeader(
          HttpHeaders.SET_COOKIE, AuthController.cookie(request, signIn.token()).toString());
      if (!signIn.created()) {
        response.sendRedirect("/");
        return;
      }
      var app = ServletUriComponentsBuilder.fromContextPath(request).replacePath("/").toUriString();
      auth.sendWelcomeMail(signIn.email(), app);
      response.sendRedirect("/?welcome");
    };
  }

  /** Auth0 turned the user away, or the callback did not check out. */
  static AuthenticationFailureHandler failed() {
    return (request, response, exception) -> {
      endRoundTrip(request);
      response.sendRedirect("/login?error=failed");
    };
  }

  private static void endRoundTrip(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
  }
}
