package io.github.kalpak44.workspacedai.api.auth;

import java.time.Instant;
import java.util.Optional;

/** Where the Management API token is kept between calls: the database, shared by every instance. */
public interface Auth0Tokens {

  record Token(String value, Instant expiresAt) {}

  Optional<Token> current();

  /** Keeps whichever expires later, so two instances refreshing at once cannot go backwards. */
  void save(Token token);
}
