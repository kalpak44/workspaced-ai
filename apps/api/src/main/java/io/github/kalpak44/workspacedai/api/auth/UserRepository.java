package io.github.kalpak44.workspacedai.api.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from UserEntity u where u.id = :id")
  Optional<UserEntity> lockById(@Param("id") UUID id);

  Optional<UserEntity> findByIdpSubject(String idpSubject);

  /**
   * Takes the account's one demo; 0 when it already had it, or another start took it first. Its own
   * transaction, committed before the VM is asked for.
   */
  @Transactional
  @Modifying
  @Query(
      "update UserEntity u set u.demoStartedAt = :now where u.id = :id and u.demoStartedAt is null")
  int claimDemo(@Param("id") UUID id, @Param("now") Instant now);

  /** Hands the demo back after its VM failed to start, so it can be tried again. */
  @Transactional
  @Modifying
  @Query("update UserEntity u set u.demoStartedAt = null where u.id = :id")
  int releaseDemo(@Param("id") UUID id);

  /** What each request is checked against. */
  Optional<Access> findAccessById(UUID id);

  interface Access {
    Role getRole();

    Instant getSessionsValidAfter();
  }
}
