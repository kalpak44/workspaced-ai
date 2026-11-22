package io.github.kalpak44.workspacedai.api.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConnectorRepository extends JpaRepository<AiConnectorEntity, UUID> {
  Optional<AiConnectorEntity> findByOwnerIdAndTrialTrue(UUID ownerId);

  List<AiConnectorEntity> findAllByOwnerIdOrderByNameAscIdAsc(UUID ownerId);

  Optional<AiConnectorEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
}
