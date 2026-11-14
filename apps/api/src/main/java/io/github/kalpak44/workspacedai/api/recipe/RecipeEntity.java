package io.github.kalpak44.workspacedai.api.recipe;

import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A recipe as stored; its ratings are their own rows, summed up when recipes are listed. */
@Entity
@Table(name = "recipe")
public class RecipeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "author_id", nullable = false, updatable = false)
  private UserEntity author;

  @Column(nullable = false, length = 100)
  private String title;

  @Column(nullable = false, length = 300)
  private String summary;

  @Column(nullable = false, length = 20)
  private String category;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]")
  private List<String> tags = new ArrayList<>();

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Visibility visibility;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  // Mapped only so queries can join it; the database cascade deletes these with the recipe.
  @OneToMany(mappedBy = "recipe")
  private List<RecipeRatingEntity> ratings = new ArrayList<>();

  protected RecipeEntity() {
    // For JPA.
  }

  public RecipeEntity(UserEntity author, RecipeRequest.Fields fields) {
    this.author = author;
    this.createdAt = Instant.now();
    apply(fields, createdAt);
  }

  /** Replaces every field; published_at is set the first time it turns public and kept after. */
  public void update(RecipeRequest.Fields fields) {
    apply(fields, Instant.now());
  }

  private void apply(RecipeRequest.Fields fields, Instant now) {
    title = fields.title();
    summary = fields.summary();
    category = fields.category();
    tags = new ArrayList<>(fields.tags());
    content = fields.content();
    visibility = fields.visibility();
    updatedAt = now;
    if (publishedAt == null && visibility == Visibility.PUBLIC) {
      publishedAt = now;
    }
  }

  public UUID getId() {
    return id;
  }
}
