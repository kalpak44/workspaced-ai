package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The one row holding the Management API token every instance shares. */
@Entity
@Table(name = "auth0_token")
public class Auth0TokenEntity {

  static final short SINGLETON_ID = 1;

  @Id private Short id;

  @Column(name = "access_token", nullable = false, columnDefinition = "text")
  private String accessToken;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected Auth0TokenEntity() {
    // For JPA.
  }

  Auth0TokenEntity(String accessToken, Instant expiresAt) {
    this.id = SINGLETON_ID;
    this.accessToken = accessToken;
    this.expiresAt = expiresAt;
  }

  String getAccessToken() {
    return accessToken;
  }

  Instant getExpiresAt() {
    return expiresAt;
  }
}
