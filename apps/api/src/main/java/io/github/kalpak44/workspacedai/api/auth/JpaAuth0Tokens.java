package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaAuth0Tokens implements Auth0Tokens {

  private final EntityManager entityManager;

  public JpaAuth0Tokens(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Token> current() {
    return Optional.ofNullable(entityManager.find(Auth0TokenEntity.class, Auth0TokenEntity.ID))
        .map(row -> new Token(row.getAccessToken(), row.getExpiresAt()));
  }

  /**
   * Replaces the row only with a token that expires later, in one conditional update, so an
   * instance whose refresh finished last never overwrites a newer token. The first save inserts it;
   * two instances inserting at once collide on the key, which {@link HttpAuth0Users} expects.
   */
  @Override
  @Transactional
  public void save(Token token) {
    var cb = entityManager.getCriteriaBuilder();
    var update = cb.createCriteriaUpdate(Auth0TokenEntity.class);
    var row = update.from(Auth0TokenEntity.class);
    update
        .set(row.get(Auth0TokenEntity_.accessToken), token.value())
        .set(row.get(Auth0TokenEntity_.expiresAt), token.expiresAt())
        .where(
            cb.equal(row.get(Auth0TokenEntity_.id), Auth0TokenEntity.ID),
            cb.lessThan(row.get(Auth0TokenEntity_.expiresAt), token.expiresAt()));
    var updated = entityManager.createQuery(update).executeUpdate();
    if (updated == 0 && entityManager.find(Auth0TokenEntity.class, Auth0TokenEntity.ID) == null) {
      entityManager.persist(new Auth0TokenEntity(token.value(), token.expiresAt()));
    }
  }
}
