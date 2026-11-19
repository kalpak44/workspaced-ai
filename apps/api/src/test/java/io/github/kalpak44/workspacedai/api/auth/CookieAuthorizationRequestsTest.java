package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.databind.json.JsonMapper;

class CookieAuthorizationRequestsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SECRET = "k".repeat(32);

  private final OAuth2AuthorizationRequest authorization =
      OAuth2AuthorizationRequest.authorizationCode()
          .authorizationUri("https://auth.example.test/authorize")
          .clientId("test-client")
          .redirectUri("https://ai.example.test/api/v1/login/oauth2/code/auth0")
          .scopes(Set.of("openid", "email"))
          .state("state-1")
          .additionalParameters(Map.of("nonce", "hashed-nonce", "acr_values", "mfa"))
          .attributes(Map.of("registration_id", "auth0", "code_verifier", "verifier-1"))
          .authorizationRequestUri("https://auth.example.test/authorize?state=state-1")
          .build();

  @Test
  void anotherInstanceWithTheSameSecretFinishesTheSignIn() {
    var cookie = saved(new CookieAuthorizationRequests(SECRET, JSON));

    var loaded =
        new CookieAuthorizationRequests(SECRET, JSON).loadAuthorizationRequest(with(cookie));

    assertThat(loaded).isNotNull();
    assertThat(loaded.getState()).isEqualTo("state-1");
    assertThat(loaded.getRedirectUri()).isEqualTo(authorization.getRedirectUri());
    assertThat(loaded.getScopes()).containsExactlyInAnyOrder("openid", "email");
    assertThat(loaded.getAdditionalParameters()).isEqualTo(authorization.getAdditionalParameters());
    assertThat(loaded.getAttributes()).isEqualTo(authorization.getAttributes());
  }

  @Test
  void keepsThePkceVerifierOutOfReach() {
    var cookie = saved(new CookieAuthorizationRequests(SECRET, JSON));
    assertThat(cookie.getValue()).doesNotContain("verifier-1", "state-1");
  }

  @Test
  void refusesACookieItDidNotWriteOrThatWasAltered() {
    var cookie = saved(new CookieAuthorizationRequests(SECRET, JSON));
    var foreign = new CookieAuthorizationRequests("o".repeat(32), JSON);
    assertThat(foreign.loadAuthorizationRequest(with(cookie))).isNull();

    var value = cookie.getValue();
    var altered = new Cookie(cookie.getName(), value.substring(0, value.length() - 2) + "AA");
    var same = new CookieAuthorizationRequests(SECRET, JSON);
    assertThat(same.loadAuthorizationRequest(with(altered))).isNull();
    assertThat(same.loadAuthorizationRequest(new MockHttpServletRequest())).isNull();
  }

  @Test
  void clearsTheCookieOnceTheCallbackHasUsedIt() {
    var repository = new CookieAuthorizationRequests(SECRET, JSON);
    var response = new MockHttpServletResponse();

    var removed = repository.removeAuthorizationRequest(with(saved(repository)), response);

    assertThat(removed).isNotNull();
    assertThat(response.getHeader("Set-Cookie"))
        .startsWith(CookieAuthorizationRequests.COOKIE + "=;")
        .contains("Max-Age=0");
  }

  private Cookie saved(CookieAuthorizationRequests repository) {
    var response = new MockHttpServletResponse();
    repository.saveAuthorizationRequest(authorization, new MockHttpServletRequest(), response);
    var header = response.getHeader("Set-Cookie");
    assertThat(header).contains("HttpOnly", "SameSite=Lax");
    var value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    return new Cookie(CookieAuthorizationRequests.COOKIE, value);
  }

  private static MockHttpServletRequest with(Cookie cookie) {
    var request = new MockHttpServletRequest();
    request.setCookies(cookie);
    return request;
  }
}
