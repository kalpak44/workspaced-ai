package io.github.kalpak44.workspacedai.api;

import io.github.kalpak44.workspacedai.api.auth.Auth0Users;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auth0's users as the tests need them. A test user's subject is "test|" plus their email, so every
 * one has a profile; names are whatever a test set.
 */
public class FakeAuth0Users implements Auth0Users {

  // Static so TestUsers can name an account as it makes one, before any context exists.
  private static final Map<String, String[]> NAMES = new ConcurrentHashMap<>();

  public static void name(String subject, String givenName, String familyName) {
    NAMES.put(subject, new String[] {givenName, familyName});
  }

  @Override
  public Optional<Profile> profile(String subject) {
    var names = NAMES.getOrDefault(subject, new String[] {"", ""});
    var email = subject.substring(subject.indexOf('|') + 1);
    return Optional.of(new Profile(email, names[0], names[1], "https://pictures.test/" + email));
  }

  @Override
  public void updateNames(String subject, String givenName, String familyName) {
    name(subject, givenName, familyName);
  }

  @Override
  public Map<String, Profile> profiles(Collection<String> subjects) {
    var found = new HashMap<String, Profile>();
    subjects.forEach(s -> profile(s).ifPresent(p -> found.put(s, p)));
    return found;
  }
}
