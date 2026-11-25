package io.github.kalpak44.workspacedai.api.auth;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface TrialEmailRepository extends JpaRepository<TrialEmailEntity, String> {

  /** 1 when this account is the first with the address; two sign-ups at once leave one row. */
  @Modifying
  @Query(
      value =
          "INSERT INTO trial_email (email_hash, user_id) VALUES (:hash, :user)"
              + " ON CONFLICT DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(String hash, UUID user);
}
