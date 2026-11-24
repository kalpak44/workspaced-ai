package io.github.kalpak44.workspacedai.api.recipe;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RecipeRepository extends JpaRepository<RecipeEntity, UUID> {

  /** Holds the recipe's row until the transaction ends, so changes to its ratings take turns. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RecipeEntity> findLockedById(UUID id);
}
