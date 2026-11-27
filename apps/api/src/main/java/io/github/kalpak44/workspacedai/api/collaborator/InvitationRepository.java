package io.github.kalpak44.workspacedai.api.collaborator;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InvitationRepository extends JpaRepository<InvitationEntity, UUID> {

  /** Held to the end of the transaction, so two tabs accepting one link cannot both succeed. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<InvitationEntity> findLockedByCodeHash(String codeHash);

  /** As above, by id. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from InvitationEntity i where i.id = :id")
  Optional<InvitationEntity> findLockedById(@Param("id") UUID id);

  Optional<InvitationEntity> findByWorkspaceIdAndEmailHash(UUID workspaceId, String emailHash);

  /** Those still open, oldest first. */
  List<InvitationEntity> findAllByWorkspaceIdAndExpiresAtAfterOrderByCreatedAtAscIdAsc(
      UUID workspaceId, Instant now);

  long countByWorkspaceIdAndExpiresAtAfter(UUID workspaceId, Instant now);

  long deleteByIdAndWorkspaceId(UUID id, UUID workspaceId);

  @Modifying
  @Query("delete from InvitationEntity i where i.expiresAt <= :now")
  int deleteExpired(@Param("now") Instant now);

  @Modifying
  @Query(
      value = "INSERT INTO invitation_mail (inviter, sent_at) VALUES (:inviter, :at)",
      nativeQuery = true)
  void recordMail(@Param("inviter") UUID inviter, @Param("at") Instant at);

  @Query(
      value = "SELECT count(*) FROM invitation_mail WHERE inviter = :inviter AND sent_at > :since",
      nativeQuery = true)
  long countMailsSince(@Param("inviter") UUID inviter, @Param("since") Instant since);

  @Modifying
  @Query(value = "DELETE FROM invitation_mail WHERE sent_at <= :before", nativeQuery = true)
  int deleteMailsBefore(@Param("before") Instant before);
}
