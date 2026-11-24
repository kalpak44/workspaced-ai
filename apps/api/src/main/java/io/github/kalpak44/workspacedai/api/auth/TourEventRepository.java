package io.github.kalpak44.workspacedai.api.auth;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TourEventRepository extends JpaRepository<TourEventEntity, Long> {

  /** In the order they happened. */
  List<TourEventEntity> findByUserIdInOrderByIdAsc(Collection<UUID> users);
}
