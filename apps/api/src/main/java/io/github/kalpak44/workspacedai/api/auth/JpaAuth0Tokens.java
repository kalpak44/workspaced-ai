package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Management API token, encrypted at rest: it can read and change every user, so a database
 * backup or a read-only role must not be enough to use it. The key comes from the shared secret,
 * which lives outside the database.
 */
@Component
public class JpaAuth0Tokens implements Auth0Tokens {

  private final EntityManager entityManager;
  private final SecretBox box;

  public JpaAuth0Tokens(EntityManager entityManager, AuthProperties properties) {
    this.entityManager = entityManager;
    this.box = new SecretBox(properties.jwtSecret(), "workspaced-auth0-token");
  }

  /** Empty too for a row this key cannot open, which the next refresh replaces. */
  @Override
  @Transactional(readOnly = true)
  public Optional<Token> current() {
    return row()
        .flatMap(row -> box.open(row.getAccessToken()).map(t -> new Token(t, row.getExpiresAt())));
  }

  /**
   * Replaces the row only with a token that expires later, in one conditional update, so an
   * instance whose refresh finished last never overwrites a newer token. The first save inserts it;
   * two instances inserting at once collide on the key, which {@link HttpAuth0Users} expects.
   */
  @Override
  @Transactional
  public void save(Token token) {
    var sealed = box.seal(token.value());
    var cb = entityManager.getCriteriaBuilder();
    var update = cb.createCriteriaUpdate(Auth0TokenEntity.class);
    var row = update.from(Auth0TokenEntity.class);
    update
        .set(row.get(Auth0TokenEntity_.accessToken), sealed)
        .set(row.get(Auth0TokenEntity_.expiresAt), token.expiresAt())
        .where(
            cb.equal(row.get(Auth0TokenEntity_.id), Auth0TokenEntity.ID),
            cb.lessThan(row.get(Auth0TokenEntity_.expiresAt), token.expiresAt()));
    var updated = entityManager.createQuery(update).executeUpdate();
    if (updated == 0 && entityManager.find(Auth0TokenEntity.class, Auth0TokenEntity.ID) == null) {
      entityManager.persist(new Auth0TokenEntity(sealed, token.expiresAt()));
    }
  }

  /** Each sealing differs, so the row is matched by what it opens to, then by its own value. */
  @Override
  @Transactional
  public void discard(String value) {
    var stored = row().filter(r -> box.open(r.getAccessToken()).filter(value::equals).isPresent());
    if (stored.isEmpty()) {
      return;
    }
    var cb = entityManager.getCriteriaBuilder();
    var delete = cb.createCriteriaDelete(Auth0TokenEntity.class);
    var row = delete.from(Auth0TokenEntity.class);
    delete.where(
        cb.equal(row.get(Auth0TokenEntity_.id), Auth0TokenEntity.ID),
        cb.equal(row.get(Auth0TokenEntity_.accessToken), stored.get().getAccessToken()));
    entityManager.createQuery(delete).executeUpdate();
  }

  private Optional<Auth0TokenEntity> row() {
    return Optional.ofNullable(entityManager.find(Auth0TokenEntity.class, Auth0TokenEntity.ID));
  }
}
