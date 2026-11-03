package io.github.kalpak44.workspacedai.api.secret;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceSecretRepository extends JpaRepository<WorkspaceSecretEntity, UUID> {

  /** Oldest first; the name breaks ties between secrets added in the same instant. */
  List<WorkspaceSecretEntity> findAllByWorkspaceIdOrderByCreatedAtAscNameAsc(UUID workspaceId);

  Optional<WorkspaceSecretEntity> findByWorkspaceIdAndName(UUID workspaceId, String name);
}
