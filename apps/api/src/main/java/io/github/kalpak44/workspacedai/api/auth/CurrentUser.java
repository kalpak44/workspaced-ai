package io.github.kalpak44.workspacedai.api.auth;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Who the current request was authenticated as: the subject of its token, and its profile. */
public final class CurrentUser {

  // The profile Auth0 vouched for at sign-in, carried so no page needs to ask Auth0 for it.
  static final String EMAIL = "email";
  static final String GIVEN_NAME = "given_name";
  static final String FAMILY_NAME = "family_name";
  static final String PICTURE = "picture";
  // Set on a token that only proves who an unverified sign-up is; it signs no one in.
  static final String USE = "use";
  static final String VERIFY_EMAIL = "verify-email";

  private CurrentUser() {}

  /** The profile in the current token; empty strings for whatever it does not carry. */
  public static Auth0Users.Profile profile() {
    var jwt = token().getToken();
    return new Auth0Users.Profile(
        text(jwt.getClaimAsString(EMAIL)),
        text(jwt.getClaimAsString(GIVEN_NAME)),
        text(jwt.getClaimAsString(FAMILY_NAME)),
        text(jwt.getClaimAsString(PICTURE)));
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static JwtAuthenticationToken token() {
    if (!(SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken token)) {
      throw new IllegalStateException("no signed-in user");
    }
    return token;
  }

  /**
   * Only called behind the security filter, which has already turned away a request without a valid
   * token.
   */
  public static UUID id() {
    return UUID.fromString(token().getToken().getSubject());
  }

  /** Whether the current request was authenticated as an admin, as its authorities say. */
  public static boolean admin() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
        && authentication.getAuthorities().stream()
            .anyMatch(a -> Role.ADMIN.authority().equals(a.getAuthority()));
  }
}
