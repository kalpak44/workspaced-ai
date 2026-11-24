package io.github.kalpak44.workspacedai.api.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkspaceDomainRepository
    extends JpaRepository<WorkspaceDomainEntity, UUID>, DomainMoves {

  List<WorkspaceDomainEntity> findAllByWorkspaceIdOrderByCreatedAt(UUID workspaceId);

  Optional<WorkspaceDomainEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

  Optional<WorkspaceDomainEntity> findByWorkspaceIdAndDomainAndHost(
      UUID workspaceId, String domain, String host);

  long countByWorkspaceId(UUID workspaceId);
}
