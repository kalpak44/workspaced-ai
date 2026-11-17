package io.github.kalpak44.workspacedai.api.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

  boolean existsByKindAndReference(String kind, String reference);

  List<LedgerEntryEntity> findAllByUserIdAndKindAndCreatedAtGreaterThanEqual(
      UUID userId, String kind, Instant since);
}
