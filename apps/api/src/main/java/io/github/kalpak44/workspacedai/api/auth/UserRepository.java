package io.github.kalpak44.workspacedai.api.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

  Optional<UserEntity> findByUsername(String username);

  boolean existsByUsername(String username);

  /** What each request is checked against, without loading the avatar. */
  Optional<Access> findAccessById(UUID id);

  interface Access {
    Role getRole();

    int getTokenVersion();
  }
}
