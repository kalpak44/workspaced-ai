package io.github.kalpak44.workspacedai.api.recipe;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** One user's stars for one recipe; rating again replaces them. */
@Entity
@Table(name = "recipe_rating")
public class RecipeRatingEntity {

  /** A class, not a record: Hibernate fills in recipeId itself through @MapsId. */
  @Embeddable
  public static class Key implements Serializable {

    @Column(name = "recipe_id")
    private UUID recipeId;

    @Column(name = "user_id")
    private UUID userId;

    protected Key() {
      // For JPA.
    }

    public Key(UUID recipeId, UUID userId) {
      this.recipeId = recipeId;
      this.userId = userId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(recipeId, key.recipeId)
          && Objects.equals(userId, key.userId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(recipeId, userId);
    }
  }

  @EmbeddedId private Key id;

  @MapsId("recipeId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "recipe_id")
  private RecipeEntity recipe;

  @Column(nullable = false)
  private short stars;

  protected RecipeRatingEntity() {
    // For JPA.
  }

  public RecipeRatingEntity(RecipeEntity recipe, UUID user, int stars) {
    this.id = new Key(recipe.getId(), user);
    this.recipe = recipe;
    this.stars = (short) stars;
  }

  public void setStars(int stars) {
    this.stars = (short) stars;
  }
}
