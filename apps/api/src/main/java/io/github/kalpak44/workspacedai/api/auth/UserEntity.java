package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** An account that signs in and owns workspaces. */
@Entity
@Table(name = "app_user")
public class UserEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  // The Auth0 user this account is. Their email, name and picture stay in Auth0.
  @Column(name = "idp_subject", nullable = false, unique = true, updatable = false)
  private String idpSubject;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role = Role.USER;

  // Set by the column default.
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  // A session token issued before this is refused; signing out moves it to now.
  @Column(name = "sessions_valid_after", insertable = false, nullable = false)
  private Instant sessionsValidAfter;

  protected UserEntity() {
    // For JPA.
  }

  public UserEntity(String idpSubject, Role role) {
    this.idpSubject = idpSubject;
    this.role = role;
  }

  public UUID getId() {
    return id;
  }

  public String getIdpSubject() {
    return idpSubject;
  }

  public Role getRole() {
    return role;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * Revokes every session token issued before now. Tokens carry whole seconds, so the time is cut
   * to the second: a sign-in later in this same second stays valid.
   */
  public void signOutEverywhere(Instant now) {
    this.sessionsValidAfter = now.truncatedTo(ChronoUnit.SECONDS);
  }

  /** Takes the role Auth0 assigns the user at this sign-in. */
  public void signedIn(Role role) {
    this.role = role;
  }
}
