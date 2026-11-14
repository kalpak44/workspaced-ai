package io.github.kalpak44.workspacedai.api.recipe;

import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserEntity_;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.stereotype.Repository;

/**
 * Reads recipes with their ratings summed up, through the Criteria API over the JPA metamodel, and
 * writes them through the entities. A listing leaves the Markdown out; only a single recipe carries
 * it. Callers hold the transaction.
 */
@Repository
public class RecipeStore {

  /** How a page of recipes is ordered; the id breaks ties, so no recipe shows on two pages. */
  public enum Order {
    TOP,
    NEWEST,
    UPDATED
  }

  /** Which recipes a listing covers, and how it narrows them. */
  public record Filter(boolean publicScope, UUID viewer, String query, String category) {}

  private static final char ESCAPE = '\\';

  // Tuple aliases for the columns that are not an entity attribute of their own name.
  private static final String AUTHOR_ID = "authorId";
  private static final String AVERAGE = "average";
  private static final String COUNT = "count";
  private static final String MY_RATING = "myRating";

  private final EntityManager entities;
  private final RecipeRepository recipes;
  private final RecipeRatingRepository ratings;

  public RecipeStore(
      EntityManager entities, RecipeRepository recipes, RecipeRatingRepository ratings) {
    this.entities = entities;
    this.recipes = recipes;
    this.ratings = ratings;
  }

  public List<Recipe> list(Filter filter, Order order, int limit, int offset) {
    var cb = builder();
    var query = cb.createTupleQuery();
    var recipe = query.from(RecipeEntity.class);
    var columns = select(cb, query, recipe, filter.viewer(), false);
    var id = recipe.get(RecipeEntity_.id);
    var updated = recipe.get(RecipeEntity_.updatedAt);
    query
        .where(matching(cb, recipe, filter))
        .orderBy(
            switch (order) {
              case TOP ->
                  List.of(
                      cb.desc(columns.average()),
                      cb.desc(columns.count()),
                      cb.desc(updated),
                      cb.asc(id));
              case NEWEST ->
                  List.of(
                      cb.desc(
                          cb.coalesce(
                              recipe.get(RecipeEntity_.publishedAt),
                              recipe.get(RecipeEntity_.createdAt))),
                      cb.asc(id));
              case UPDATED -> List.of(cb.desc(updated), cb.asc(id));
            });
    return entities
        .createQuery(query)
        .setFirstResult(offset)
        .setMaxResults(limit)
        .getResultStream()
        .map(row -> recipe(row, filter.viewer(), false))
        .toList();
  }

  public long count(Filter filter) {
    var cb = builder();
    var query = cb.createQuery(Long.class);
    var recipe = query.from(RecipeEntity.class);
    query.select(cb.count(recipe)).where(matching(cb, recipe, filter));
    return entities.createQuery(query).getSingleResult();
  }

  /** Any recipe, whoever may see it; the caller decides that. */
  public Optional<Recipe> find(UUID id, UUID viewer) {
    var cb = builder();
    var query = cb.createTupleQuery();
    var recipe = query.from(RecipeEntity.class);
    select(cb, query, recipe, viewer, true);
    query.where(cb.equal(recipe.get(RecipeEntity_.id), id));
    return entities
        .createQuery(query)
        .getResultStream()
        .findFirst()
        .map(row -> recipe(row, viewer, true));
  }

  public UUID create(UUID author, RecipeRequest.Fields fields) {
    return recipes
        .save(new RecipeEntity(entities.getReference(UserEntity.class, author), fields))
        .getId();
  }

  public void update(UUID id, RecipeRequest.Fields fields) {
    recipes.findById(id).ifPresent(recipe -> recipe.update(fields));
  }

  public void delete(UUID id) {
    recipes.deleteById(id);
  }

  /**
   * Rating again replaces the user's earlier rating. The recipe's row is locked first, so two
   * ratings sent at once by the same user cannot both insert and collide on the key.
   */
  public void rate(UUID recipeId, UUID user, int stars) {
    var recipe = recipes.lockById(recipeId).orElseThrow();
    ratings
        .findById(new RecipeRatingEntity.Key(recipeId, user))
        .ifPresentOrElse(
            rating -> rating.setStars(stars),
            () -> ratings.save(new RecipeRatingEntity(recipe, user, stars)));
  }

  public void unrate(UUID recipe, UUID user) {
    ratings.deleteById(new RecipeRatingEntity.Key(recipe, user));
  }

  private HibernateCriteriaBuilder builder() {
    return (HibernateCriteriaBuilder) entities.getCriteriaBuilder();
  }

  /** The aggregates a listing also orders by. */
  private record Columns(Expression<Double> average, Expression<Long> count) {}

  // Every user's ratings are averaged through the join; the viewer's own comes from a subquery,
  // so it does not narrow what is averaged. Counting stars, not the join, keeps a recipe with no
  // ratings at zero: its one joined row is all nulls.
  private static Columns select(
      HibernateCriteriaBuilder cb,
      CriteriaQuery<Tuple> query,
      Root<RecipeEntity> recipe,
      UUID viewer,
      boolean withContent) {
    var author = recipe.join(RecipeEntity_.author);
    var rating = recipe.join(RecipeEntity_.ratings, JoinType.LEFT);
    var stars = rating.get(RecipeRatingEntity_.stars);
    var average = cb.coalesce(cb.avg(stars), 0.0);
    var count = cb.count(stars);

    var own = query.subquery(Short.class);
    var mine = own.from(RecipeRatingEntity.class);
    own.select(mine.get(RecipeRatingEntity_.stars))
        .where(
            cb.equal(mine.get(RecipeRatingEntity_.recipe), recipe),
            cb.equal(
                mine.get(RecipeRatingEntity_.id).get(RecipeRatingEntity_.Key_.userId), viewer));

    var selection =
        new ArrayList<Selection<?>>(
            List.of(
                recipe.get(RecipeEntity_.id).alias(RecipeEntity_.ID),
                recipe.get(RecipeEntity_.title).alias(RecipeEntity_.TITLE),
                recipe.get(RecipeEntity_.summary).alias(RecipeEntity_.SUMMARY),
                recipe.get(RecipeEntity_.category).alias(RecipeEntity_.CATEGORY),
                recipe.get(RecipeEntity_.tags).alias(RecipeEntity_.TAGS),
                recipe.get(RecipeEntity_.visibility).alias(RecipeEntity_.VISIBILITY),
                recipe.get(RecipeEntity_.createdAt).alias(RecipeEntity_.CREATED_AT),
                recipe.get(RecipeEntity_.updatedAt).alias(RecipeEntity_.UPDATED_AT),
                recipe.get(RecipeEntity_.publishedAt).alias(RecipeEntity_.PUBLISHED_AT),
                author.get(UserEntity_.id).alias(AUTHOR_ID),
                author.get(UserEntity_.username).alias(UserEntity_.USERNAME),
                author.get(UserEntity_.firstName).alias(UserEntity_.FIRST_NAME),
                author.get(UserEntity_.lastName).alias(UserEntity_.LAST_NAME),
                average.alias(AVERAGE),
                count.alias(COUNT),
                own.alias(MY_RATING)));
    if (withContent) {
      selection.add(recipe.get(RecipeEntity_.content).alias(RecipeEntity_.CONTENT));
    }
    query.multiselect(selection).groupBy(recipe.get(RecipeEntity_.id), author.get(UserEntity_.id));
    return new Columns(average, count);
  }

  private static Predicate[] matching(
      HibernateCriteriaBuilder cb, Root<RecipeEntity> recipe, Filter filter) {
    var where = new ArrayList<Predicate>();
    if (filter.publicScope()) {
      where.add(cb.equal(recipe.get(RecipeEntity_.visibility), Visibility.PUBLIC));
    } else {
      where.add(cb.equal(recipe.get(RecipeEntity_.visibility), Visibility.PRIVATE));
      where.add(cb.equal(recipe.get(RecipeEntity_.author).get(UserEntity_.id), filter.viewer()));
    }
    if (filter.category() != null) {
      where.add(cb.equal(recipe.get(RecipeEntity_.category), filter.category()));
    }
    if (filter.query() != null) {
      var like = "%" + escapeLike(filter.query().toLowerCase(Locale.ROOT)) + "%";
      where.add(
          cb.or(
              contains(cb, recipe.get(RecipeEntity_.title), like),
              contains(cb, recipe.get(RecipeEntity_.summary), like),
              contains(cb, cb.collectionToString(recipe.get(RecipeEntity_.tags), " "), like)));
    }
    return where.toArray(Predicate[]::new);
  }

  private static Predicate contains(
      HibernateCriteriaBuilder cb, Expression<String> field, String like) {
    return cb.like(cb.lower(field), like, ESCAPE);
  }

  /** The user's text is matched literally: % and _ in it are not wildcards. */
  static String escapeLike(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  @SuppressWarnings("unchecked")
  private static Recipe recipe(Tuple row, UUID viewer, boolean withContent) {
    var author = row.get(AUTHOR_ID, UUID.class);
    var own = row.get(MY_RATING, Short.class);
    var tags = (List<String>) row.get(RecipeEntity_.TAGS);
    return new Recipe(
        row.get(RecipeEntity_.ID, UUID.class),
        row.get(RecipeEntity_.TITLE, String.class),
        row.get(RecipeEntity_.SUMMARY, String.class),
        row.get(RecipeEntity_.CATEGORY, String.class),
        tags == null ? List.of() : List.copyOf(tags),
        row.get(RecipeEntity_.VISIBILITY, Visibility.class),
        new RecipeAuthor(
            author,
            row.get(UserEntity_.USERNAME, String.class),
            row.get(UserEntity_.FIRST_NAME, String.class),
            row.get(UserEntity_.LAST_NAME, String.class)),
        row.get(RecipeEntity_.CREATED_AT, Instant.class),
        row.get(RecipeEntity_.UPDATED_AT, Instant.class),
        row.get(RecipeEntity_.PUBLISHED_AT, Instant.class),
        RecipeRating.of(
            row.get(AVERAGE, Double.class), Math.toIntExact(row.get(COUNT, Long.class))),
        own == null ? null : own.intValue(),
        author.equals(viewer),
        withContent ? row.get(RecipeEntity_.CONTENT, String.class) : null);
  }
}
