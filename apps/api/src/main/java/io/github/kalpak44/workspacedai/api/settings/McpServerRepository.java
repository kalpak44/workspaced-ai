package io.github.kalpak44.workspacedai.api.settings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRepository extends JpaRepository<McpServerEntity, UUID> {

  List<McpServerEntity> findAllByWorkspaceIdOrderByCreatedAtAscNameAsc(UUID workspaceId);

  Optional<McpServerEntity> findByWorkspaceIdAndName(UUID workspaceId, String name);
}
