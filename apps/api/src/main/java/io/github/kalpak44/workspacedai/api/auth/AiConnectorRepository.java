package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AiConnectorRepository extends JpaRepository<AiConnectorEntity, UUID> {

  /** Held to the end of the transaction. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AiConnectorEntity> findLockedById(UUID id);

  List<AiConnectorEntity> findByProvider(String provider, Pageable page);

  /** OpenRouter connectors checked least recently first, and those never checked before all. */
  default List<AiConnectorEntity> findForStatusCheck(int limit) {
    var order =
        Sort.by(
            Sort.Order.asc(AiConnectorEntity_.STATUS_CHECKED_AT).nullsFirst(),
            Sort.Order.asc(AiConnectorEntity_.ID));
    return findByProvider("openrouter", PageRequest.of(0, limit, order));
  }

  Optional<AiConnectorEntity> findByOwnerIdAndTrialTrue(UUID ownerId);

  List<AiConnectorEntity> findAllByOwnerIdOrderByNameAscIdAsc(UUID ownerId);

  Optional<AiConnectorEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
}
