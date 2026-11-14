package io.github.kalpak44.workspacedai.api.recipe;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeRatingRepository
    extends JpaRepository<RecipeRatingEntity, RecipeRatingEntity.Key> {}
