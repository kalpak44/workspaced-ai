package io.github.kalpak44.workspacedai.api.referral;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ReferralCodeRepository extends JpaRepository<ReferralCodeEntity, UUID> {

  Optional<ReferralCodeEntity> findByCode(String code);

  /**
   * Writes nothing when the user already has a code, or the code is taken, so two first visits at
   * once or a collision never fail the transaction.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO referral_code (user_id, code) VALUES (:user, :code) ON CONFLICT DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(UUID user, String code);
}
