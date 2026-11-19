package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "verification_email")
public class VerificationEmailEntity {

  @Id
  @Column(name = "idp_subject")
  private String idpSubject;

  @Column(name = "sent_at", nullable = false)
  private Instant sentAt;

  protected VerificationEmailEntity() {
    // For JPA.
  }

  VerificationEmailEntity(String idpSubject, Instant sentAt) {
    this.idpSubject = idpSubject;
    this.sentAt = sentAt;
  }
}
