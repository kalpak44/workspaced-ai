package io.github.kalpak44.workspacedai.api.collaborator;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CollaboratorRepository extends JpaRepository<CollaboratorEntity, CollaboratorEntity.Key> {

  /** In the order they joined. */
  List<CollaboratorEntity> findAllByWorkspaceIdOrderByJoinedAtAscUserIdAsc(UUID workspaceId);

  long countByWorkspaceId(UUID workspaceId);

  boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

  Optional<CollaboratorEntity> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

  long deleteByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);
}
