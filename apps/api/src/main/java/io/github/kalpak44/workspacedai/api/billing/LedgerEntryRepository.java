package io.github.kalpak44.workspacedai.api.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

  boolean existsByKindAndReference(String kind, String reference);

  List<LedgerEntryEntity> findAllByUserIdAndKindAndCreatedAtGreaterThanEqual(
      UUID userId, String kind, Instant since);

  /** Everything a paid top-up credited: the top-up itself and any reward posted with it. */
  List<LedgerEntryEntity> findAllByReference(String reference);

  /** The sum of the kind's entries whose references start with the prefix, taken literally. */
  default long sumByKindAndReferencePrefix(String kind, String prefix) {
    return sumByKindAndReferenceLike(kind, prefix.replaceAll("[!%_]", "!$0") + "%");
  }

  @Query(
      "SELECT COALESCE(SUM(e.amountMicros), 0) FROM LedgerEntryEntity e"
          + " WHERE e.kind = :kind AND e.reference LIKE :pattern ESCAPE '!'")
  long sumByKindAndReferenceLike(String kind, String pattern);
}
