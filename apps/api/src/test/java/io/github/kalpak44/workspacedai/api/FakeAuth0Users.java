package io.github.kalpak44.workspacedai.api;

import io.github.kalpak44.workspacedai.api.auth.Auth0Users;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Auth0's users as the tests need them. A test user's subject is "test|" plus their email, so every
 * one has a profile; names are whatever a test set.
 */
public class FakeAuth0Users implements Auth0Users {

  // Static so TestUsers can name an account as it makes one, before any context exists.
  private static final Map<String, String[]> NAMES = new ConcurrentHashMap<>();

  // Every subject Auth0 was asked to send a verification email for.
  public static final List<String> VERIFICATION_EMAILS = new CopyOnWriteArrayList<>();

  public static void name(String subject, String givenName, String familyName) {
    NAMES.put(subject, new String[] {givenName, familyName});
  }

  /** The profile Auth0 would have for a test user: names as a test set them. */
  public static Profile profile(String subject) {
    var names = NAMES.getOrDefault(subject, new String[] {"", ""});
    var email = subject.substring(subject.indexOf('|') + 1);
    return new Profile(email, names[0], names[1], "https://pictures.test/" + email);
  }

  @Override
  public void updateNames(String subject, String givenName, String familyName) {
    name(subject, givenName, familyName);
  }

  @Override
  public void sendVerificationEmail(String subject) {
    VERIFICATION_EMAILS.add(subject);
  }
}
