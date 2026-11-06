package io.github.kalpak44.workspacedai.api.auth;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Who the current request was authenticated as: the subject of its token. */
public final class CurrentUser {

  static final String USERNAME_CLAIM = "username";

  private CurrentUser() {}

  /**
   * Only called behind the security filter, which has already turned away a request without a valid
   * token.
   */
  public static UUID id() {
    if (!(SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken token)) {
      throw new IllegalStateException("no signed-in user");
    }
    return UUID.fromString(token.getToken().getSubject());
  }
}
