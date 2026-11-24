package io.github.kalpak44.workspacedai.api.recipe;

import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Links the private Ciela recipe to its publisher on their next verified sign-in. */
@Component
public class StarterRecipes {

  private static final UUID CIELA_RECIPE = UUID.fromString("00000000-0000-4000-8000-00000000b008");
  // The placeholder author the seed migration gave the recipe until its publisher signs in.
  private static final UUID SEED_AUTHOR = UUID.fromString("00000000-0000-4000-8000-000000000a01");

  private final EntityManager entityManager;

  public StarterRecipes(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  // Called only after AuthService has checked the verified email, never from a profile edit.
  @Transactional
  public void claimPublisher(UUID userId, String verifiedEmail) {
    if (!"pavel.usanli@gmail.com".equalsIgnoreCase(verifiedEmail)) {
      return;
    }
    var cb = entityManager.getCriteriaBuilder();
    var update = cb.createCriteriaUpdate(RecipeEntity.class);
    var row = update.from(RecipeEntity.class);
    update
        .set(row.get(RecipeEntity_.author), entityManager.getReference(UserEntity.class, userId))
        .where(
            cb.equal(row.get(RecipeEntity_.id), CIELA_RECIPE),
            cb.equal(
                row.get(RecipeEntity_.author),
                entityManager.getReference(UserEntity.class, SEED_AUTHOR)));
    entityManager.createQuery(update).executeUpdate();
  }
}
