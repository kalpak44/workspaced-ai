package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UserRepository extends JpaRepository<UserEntity, UUID>, DemoClaims {

  /** Held to the end of the transaction. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<UserEntity> findLockedById(UUID id);

  Optional<UserEntity> findByIdpSubject(String idpSubject);

  /** Everyone with the role as of their last sign-in, which is when Auth0's roles are copied. */
  List<UserEntity> findByRole(Role role);

  /** Accounts made in [from, to) whose role is not the one given. */
  List<UserEntity> findByRoleNotAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
      Role role, Instant from, Instant to);

  /** What each request is checked against. */
  Optional<Access> findAccessById(UUID id);

  interface Access {
    Role getRole();

    Instant getSessionsValidAfter();
  }
}
