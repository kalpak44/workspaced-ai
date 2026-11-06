package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

  protected UserEntity() {
    // For JPA.
  }

  public UserEntity(String username, String passwordHash) {
    this.username = username;
    this.passwordHash = passwordHash;
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

  public void setProfile(String firstName, String lastName, String avatar) {
    this.firstName = firstName;
    this.lastName = lastName;
    this.avatar = avatar;
  }
}
