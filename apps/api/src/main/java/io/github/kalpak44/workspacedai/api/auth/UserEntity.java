package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

  /** The account V9 creates as admin/admin, which owns every workspace made before accounts. */
  public static final UUID DEFAULT_ADMIN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true, length = 100)
  private String username;

  // Encoded by the PasswordEncoder, prefix included; the password itself is never stored.
  @Column(name = "password_hash", nullable = false, length = 200)
  private String passwordHash;

  @Column(name = "first_name", nullable = false, length = 100)
  private String firstName = "";

  @Column(name = "last_name", nullable = false, length = 100)
  private String lastName = "";

  @Column(columnDefinition = "text")
  private String avatar;

  // Set by the column default; read only to order the user list.
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  protected UserEntity() {
    // For JPA.
  }

  public UserEntity(String username, String passwordHash) {
    this.username = username;
    this.passwordHash = passwordHash;
  }

  public UserEntity(String username, String passwordHash, String firstName, String lastName) {
    this(username, passwordHash);
    this.firstName = firstName;
    this.lastName = lastName;
  }

  public UUID getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setProfile(String firstName, String lastName, String avatar) {
    this.firstName = firstName;
    this.lastName = lastName;
    this.avatar = avatar;
  }
}
