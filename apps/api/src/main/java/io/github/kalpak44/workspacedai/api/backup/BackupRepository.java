package io.github.kalpak44.workspacedai.api.backup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The progress updates run on a follower thread, outside any request, so each is its own
 * transaction, and each touches only a backup still running: one deleted or already finished is
 * left as it is.
 */
public interface BackupRepository extends JpaRepository<BackupEntity, UUID> {

  /** Newest first; the id breaks ties between backups made in the same instant. */
  List<BackupEntity> findAllByOwnerIdOrderByCreatedAtDescIdDesc(UUID ownerId);

  Optional<BackupEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

  List<BackupEntity> findAllByStatus(BackupStatus status);

  boolean existsByWorkspaceIdAndStatus(UUID workspaceId, BackupStatus status);

  @Transactional
  @Modifying
  @Query(
      "update BackupEntity b set b.phase = :phase, b.progress = :progress"
          + " where b.id = :id and b.status = io.github.kalpak44.workspacedai.api.backup"
          + ".BackupStatus.RUNNING")
  int progress(@Param("id") UUID id, @Param("phase") String phase, @Param("progress") int progress);

  @Transactional
  @Modifying
  @Query(
      "update BackupEntity b set b.status = io.github.kalpak44.workspacedai.api.backup"
          + ".BackupStatus.COMPLETED, b.phase = 'done', b.progress = 100,"
          + " b.sizeBytes = :size, b.completedAt = :at"
          + " where b.id = :id and b.status = io.github.kalpak44.workspacedai.api.backup"
          + ".BackupStatus.RUNNING")
  int complete(@Param("id") UUID id, @Param("size") long size, @Param("at") Instant at);

  @Transactional
  @Modifying
  @Query(
      "update BackupEntity b set b.status = io.github.kalpak44.workspacedai.api.backup"
          + ".BackupStatus.FAILED, b.phase = 'failed', b.error = :error, b.completedAt = :at"
          + " where b.id = :id and b.status = io.github.kalpak44.workspacedai.api.backup"
          + ".BackupStatus.RUNNING")
  int fail(@Param("id") UUID id, @Param("error") String error, @Param("at") Instant at);
}
