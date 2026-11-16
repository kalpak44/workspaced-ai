package io.github.kalpak44.workspacedai.api.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

interface WorkspaceDomainRepository extends JpaRepository<WorkspaceDomainEntity, UUID> {

  List<WorkspaceDomainEntity> findAllByWorkspaceIdOrderByCreatedAt(UUID workspaceId);

  Optional<WorkspaceDomainEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

  Optional<WorkspaceDomainEntity> findByWorkspaceIdAndDomainAndHost(
      UUID workspaceId, String domain, String host);

  long countByWorkspaceId(UUID workspaceId);

  /**
   * Moves it from one status to the other only if it is still in the first, so a check never brings
   * back one detached since it was read.
   *
   * @return whether it moved
   */
  @Transactional
  @Modifying
  @Query(
      "update WorkspaceDomainEntity d set d.status = :to, d.verifiedAt = :at"
          + " where d.id = :id and d.status = :from")
  int move(UUID id, DomainStatus from, DomainStatus to, Instant at);
}
