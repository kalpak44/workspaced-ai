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

  // Every column below is written once: a recipe never changes after it is created, so a rating
  // always refers to exactly what was published.
  @Column(nullable = false, length = 100, updatable = false)
  private String title;

  @Column(nullable = false, length = 300, updatable = false)
  private String summary;

  @Column(nullable = false, length = 20, updatable = false)
  private String category;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]", updatable = false)
  private List<String> tags = new ArrayList<>();

  @Column(nullable = false, columnDefinition = "text", updatable = false)
  private String content;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private Visibility visibility;

  // The author's name as it was when they created it: the byline other users see.
  @Column(name = "author_first_name", nullable = false, length = 100, updatable = false)
  private String authorFirstName;

  @Column(name = "author_last_name", nullable = false, length = 100, updatable = false)
  private String authorLastName;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "published_at", updatable = false)
  private Instant publishedAt;

  // Mapped only so queries can join it; the database cascade deletes these with the recipe.
  @OneToMany(mappedBy = "recipe")
  private List<RecipeRatingEntity> ratings = new ArrayList<>();

  protected RecipeEntity() {
    // For JPA.
  }

  public RecipeEntity(
      UserEntity author,
      String authorFirstName,
      String authorLastName,
      RecipeRequest.Fields fields) {
    this.author = author;
    this.authorFirstName = authorFirstName;
    this.authorLastName = authorLastName;
    this.createdAt = Instant.now();
    this.title = fields.title();
    this.summary = fields.summary();
    this.category = fields.category();
    this.tags = new ArrayList<>(fields.tags());
    this.content = fields.content();
    this.visibility = fields.visibility();
    this.publishedAt = visibility == Visibility.PUBLIC ? createdAt : null;
  }

  public UUID getId() {
    return id;
  }
}
