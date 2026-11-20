package io.github.kalpak44.workspacedai.api.recipe;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Links the seeded tutorial recipe to its publisher on their next verified sign-in. */
@Component
public class StarterRecipes {
  private final JdbcTemplate jdbc;

  public StarterRecipes(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // Called only after AuthService has checked the verified email, never from a profile edit.
  public void claimPublisher(UUID userId, String verifiedEmail) {
    if (!"pavel.usanli@gmail.com".equalsIgnoreCase(verifiedEmail)) {
      return;
    }
    jdbc.update(
        """
        UPDATE recipe SET author_id = ?
        WHERE id = '00000000-0000-4000-8000-00000000b001'
          AND author_id = '00000000-0000-4000-8000-000000000a01'
        """,
        userId);
  }
}
