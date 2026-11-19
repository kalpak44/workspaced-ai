package io.github.kalpak44.workspacedai.api.auth;

import java.util.Collection;
import java.util.Map;

/**
 * A user's email, name and picture, which live in Auth0 alone; the app's database keeps only who a
 * user is and what they may do.
 */
public interface Auth0Users {

  /**
   * @param picture a URL: the user's Gravatar, or Auth0's picture of their initials
   */
  record Profile(String email, String givenName, String familyName, String picture) {}

  void updateNames(String subject, String givenName, String familyName);

  /** Has Auth0 send the user its email-verification message again. */
  void sendVerificationEmail(String subject);

  /** By subject; one Auth0 does not know, or does not answer for, is left out. */
  Map<String, Profile> profiles(Collection<String> subjects);
}
