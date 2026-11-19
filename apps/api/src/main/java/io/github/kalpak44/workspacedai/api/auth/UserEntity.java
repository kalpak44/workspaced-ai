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
import java.util.UUID;

/** An account that signs in and owns workspaces. */
@Entity
@Table(name = "app_user")
public class UserEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  // The Auth0 user this account is; it never changes, while the email may.
  @Column(name = "idp_subject", nullable = false, unique = true, updatable = false)
  private String idpSubject;

  // Always lowercased, which the column checks.
  @Column(nullable = false, unique = true, length = 254)
  private String email;

  @Column(name = "first_name", nullable = false, length = 100)
  private String firstName = "";

  @Column(name = "last_name", nullable = false, length = 100)
  private String lastName = "";

  @Column(columnDefinition = "text")
  private String avatar;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role = Role.USER;

  // Set by the column default.
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  protected UserEntity() {
    // For JPA.
  }

  public UserEntity(String idpSubject, String email, Role role) {
    this.idpSubject = idpSubject;
    this.email = email;
    this.role = role;
  }

  public UUID getId() {
    return id;
  }

  public String getIdpSubject() {
    return idpSubject;
  }

  public String getEmail() {
    return email;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public String getAvatar() {
    return avatar;
  }

  public Role getRole() {
    return role;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * Takes what Auth0 says about the user at this sign-in: the email it has verified, so a changed
   * address reaches notifications too, and the role it assigns.
   */
  public void signedIn(String email, Role role) {
    this.email = email;
    this.role = role;
  }

  public void setProfile(String firstName, String lastName, String avatar) {
    this.firstName = firstName;
    this.lastName = lastName;
    this.avatar = avatar;
  }
}
