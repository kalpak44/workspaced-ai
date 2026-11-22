package io.github.kalpak44.workspacedai.api.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConnectorRepository extends JpaRepository<AiConnectorEntity, UUID> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select c from AiConnectorEntity c where c.id = :id")
  Optional<AiConnectorEntity> lockById(
      @org.springframework.data.repository.query.Param("id") UUID id);

  @org.springframework.data.jpa.repository.Query(
      "select c from AiConnectorEntity c where c.provider = 'openrouter' order by c.statusCheckedAt asc nulls first, c.id")
  List<AiConnectorEntity> findForStatusCheck(org.springframework.data.domain.Pageable page);

  Optional<AiConnectorEntity> findByOwnerIdAndTrialTrue(UUID ownerId);

  List<AiConnectorEntity> findAllByOwnerIdOrderByNameAscIdAsc(UUID ownerId);

  Optional<AiConnectorEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
}
