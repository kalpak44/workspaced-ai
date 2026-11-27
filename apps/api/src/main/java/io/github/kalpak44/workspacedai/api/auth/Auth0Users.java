package io.github.kalpak44.workspacedai.api.auth;

import java.util.List;

/**
 * A user's email, name and picture, which live in Auth0 alone; the app's database keeps only who a
 * user is and what they may do.
 */
public interface Auth0Users {

  /**
   * @param picture a URL: the user's Gravatar, or Auth0's picture of their initials
   */
  record Profile(String email, String givenName, String familyName, String picture) {}

  /** The address Auth0 has for the user; blank when it has none. */
  String email(String subject);

  /** The subjects of every Auth0 user with that address; empty when there is none. */
  List<String> subjectsByEmail(String email);

  void updateNames(String subject, String givenName, String familyName);

  /** Has Auth0 send the user its email-verification message again. */
  void sendVerificationEmail(String subject);
}
