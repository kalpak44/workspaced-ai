package io.github.kalpak44.workspacedai.api.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {

  /** Oldest first; the id breaks ties between workspaces created in the same instant. */
  List<WorkspaceEntity> findAllByOrderByCreatedAtAscIdAsc();
}
