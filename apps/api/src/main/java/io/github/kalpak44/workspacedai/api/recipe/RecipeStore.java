package io.github.kalpak44.workspacedai.api.recipe;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecipeStore {

  /** How a page of recipes is ordered; the id breaks ties, so no recipe shows on two pages. */
  public enum Order {
    TOP("average DESC, count DESC, r.updated_at DESC, r.id"),
    NEWEST("COALESCE(r.published_at, r.created_at) DESC, r.id"),
    UPDATED("r.updated_at DESC, r.id");

    private final String sql;

    Order(String sql) {
      this.sql = sql;
    }
  }

  /** Which recipes a listing covers, and how it narrows them. */
  public record Filter(boolean publicScope, UUID viewer, String query, String category) {}

  // The caller's own rating comes from the second join; avg is over every user's.
  private static final String SELECT =
      """
      SELECT r.id, r.title, r.summary, r.category, r.tags, r.visibility, r.created_at,
             r.updated_at, r.published_at, r.author_id, u.username, u.first_name, u.last_name,
             COALESCE(s.average, 0) AS average, COALESCE(s.count, 0) AS count,
             m.stars AS my_rating, %s
      FROM recipe r
      JOIN app_user u ON u.id = r.author_id
      LEFT JOIN (SELECT recipe_id, AVG(stars)::float8 AS average, COUNT(*) AS count
                 FROM recipe_rating GROUP BY recipe_id) s ON s.recipe_id = r.id
      LEFT JOIN recipe_rating m ON m.recipe_id = r.id AND m.user_id = ?
      """;

  private final JdbcTemplate jdbc;

  public RecipeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Recipe> list(Filter filter, Order order, int limit, long offset) {
    var args = new ArrayList<Object>();
    args.add(filter.viewer());
    var where = where(filter, args);
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        SELECT.formatted("NULL AS content")
            + where
            + " ORDER BY "
            + order.sql
            + " LIMIT ? OFFSET ?",
        (rs, _) -> recipe(rs, filter.viewer()),
        args.toArray());
  }

  public long count(Filter filter) {
    var args = new ArrayList<Object>();
    var where = where(filter, args);
    var count =
        jdbc.queryForObject("SELECT COUNT(*) FROM recipe r" + where, Long.class, args.toArray());
    return count == null ? 0 : count;
  }

  /** Any recipe, whoever may see it; the caller decides that. */
  public Optional<Recipe> find(UUID id, UUID viewer) {
    return jdbc
        .query(
            SELECT.formatted("r.content") + " WHERE r.id = ?",
            (rs, _) -> recipe(rs, viewer),
            viewer,
            id)
        .stream()
        .findFirst();
  }

  public UUID create(UUID author, RecipeRequest.Fields fields) {
    var id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO recipe (id, author_id, title, summary, category, tags, content, visibility,
                            published_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'PUBLIC' THEN now() END)
        """,
        id,
        author,
        fields.title(),
        fields.summary(),
        fields.category(),
        fields.tags().toArray(String[]::new),
        fields.content(),
        fields.visibility().name(),
        fields.visibility().name());
    return id;
  }

  /** Replaces every field; published_at is set the first time it turns public and kept after. */
  public void update(UUID id, RecipeRequest.Fields fields) {
    jdbc.update(
        """
        UPDATE recipe SET title = ?, summary = ?, category = ?, tags = ?, content = ?,
               visibility = ?, updated_at = now(),
               published_at = COALESCE(published_at, CASE WHEN ? = 'PUBLIC' THEN now() END)
        WHERE id = ?
        """,
        fields.title(),
        fields.summary(),
        fields.category(),
        fields.tags().toArray(String[]::new),
        fields.content(),
        fields.visibility().name(),
        fields.visibility().name(),
        id);
  }

  public void delete(UUID id) {
    jdbc.update("DELETE FROM recipe WHERE id = ?", id);
  }

  /** Rating again replaces the user's earlier rating. */
  public void rate(UUID recipe, UUID user, int stars) {
    jdbc.update(
        """
        INSERT INTO recipe_rating (recipe_id, user_id, stars) VALUES (?, ?, ?)
        ON CONFLICT (recipe_id, user_id) DO UPDATE SET stars = EXCLUDED.stars
        """,
        recipe,
        user,
        stars);
  }

  public void unrate(UUID recipe, UUID user) {
    jdbc.update("DELETE FROM recipe_rating WHERE recipe_id = ? AND user_id = ?", recipe, user);
  }

  private static String where(Filter filter, List<Object> args) {
    var clauses = new ArrayList<String>();
    if (filter.publicScope()) {
      clauses.add("r.visibility = 'PUBLIC'");
    } else {
      clauses.add("r.visibility = 'PRIVATE' AND r.author_id = ?");
      args.add(filter.viewer());
    }
    if (filter.category() != null) {
      clauses.add("r.category = ?");
      args.add(filter.category());
    }
    if (filter.query() != null) {
      var like = "%" + filter.query().replaceAll("[\\\\%_]", "\\\\$0") + "%";
      clauses.add(
          "(r.title ILIKE ? OR r.summary ILIKE ? OR EXISTS (SELECT 1 FROM unnest(r.tags) t"
              + " WHERE t ILIKE ?))");
      args.addAll(List.of(like, like, like));
    }
    return " WHERE " + String.join(" AND ", clauses);
  }

  private static Recipe recipe(ResultSet rs, UUID viewer) throws SQLException {
    var author = rs.getObject("author_id", UUID.class);
    var myRating = rs.getObject("my_rating", Integer.class);
    return new Recipe(
        rs.getObject("id", UUID.class),
        rs.getString("title"),
        rs.getString("summary"),
        rs.getString("category"),
        Arrays.asList((String[]) rs.getArray("tags").getArray()),
        Visibility.valueOf(rs.getString("visibility")),
        new RecipeAuthor(
            author,
            rs.getString("username"),
            rs.getString("first_name"),
            rs.getString("last_name")),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        instant(rs, "published_at"),
        RecipeRating.of(rs.getDouble("average"), rs.getInt("count")),
        myRating,
        author.equals(viewer),
        rs.getString("content"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var time = rs.getObject(column, OffsetDateTime.class);
    return time == null ? null : time.toInstant();
  }
}
