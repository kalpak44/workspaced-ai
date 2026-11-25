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
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A recipe applied in a workspace; applying it there again changes nothing. */
@Entity
@Table(name = "recipe_use")
public class RecipeUseEntity {

  /** A class, not a record: Hibernate fills in recipeId itself through @MapsId. */
  @Embeddable
  public static class Key implements Serializable {

    @Column(name = "recipe_id")
    private UUID recipeId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    protected Key() {
      // For JPA.
    }

    public Key(UUID recipeId, UUID workspaceId) {
      this.recipeId = recipeId;
      this.workspaceId = workspaceId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(recipeId, key.recipeId)
          && Objects.equals(workspaceId, key.workspaceId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(recipeId, workspaceId);
    }
  }

  @EmbeddedId private Key id;

  @MapsId("recipeId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "recipe_id")
  private RecipeEntity recipe;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "first_used_at", nullable = false, updatable = false)
  private Instant firstUsedAt;

  protected RecipeUseEntity() {
    // For JPA.
  }

  public RecipeUseEntity(RecipeEntity recipe, UUID workspaceId, UUID userId) {
    this.id = new Key(recipe.getId(), workspaceId);
    this.recipe = recipe;
    this.userId = userId;
    this.firstUsedAt = Instant.now();
  }
}
