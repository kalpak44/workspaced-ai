package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Pending device authorization. Provider state is encrypted and never serialized. */
@Entity
@Table(name = "chatgpt_login")
class ChatGptLogin {
  @Id UUID id;

  @Column(nullable = false)
  UUID ownerId;

  @Column(nullable = false)
  UUID workspaceId;

  @Column(nullable = false)
  String name;

  @Column(columnDefinition = "text")
  String encryptedState;

  @Column(nullable = false)
  Instant expiresAt;

  @Column(nullable = false)
  Instant nextPollAt;

  @Column(nullable = false)
  int pollSeconds;

  UUID connectorId;
}

interface ChatGptLoginRepository extends JpaRepository<ChatGptLogin, UUID> {
  boolean existsByWorkspaceIdAndEncryptedStateIsNotNullAndExpiresAtAfter(
      UUID workspaceId, Instant now);

  void deleteByExpiresAtBefore(Instant before);
}
