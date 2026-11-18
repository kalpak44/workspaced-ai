package io.github.kalpak44.workspacedai.api.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** An address mail can be sent from, and the template its messages go out in. */
@Entity
@Table(name = "mail_sender")
class MailSenderEntity {

  @Id
  @Column(name = "local_part", length = 64)
  private String localPart;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(nullable = false, columnDefinition = "text")
  private String signature;

  @Column(nullable = false, length = 300)
  private String footer;

  @Column(nullable = false)
  private short position;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MailSenderEntity() {}

  String getLocalPart() {
    return localPart;
  }

  String getName() {
    return name;
  }

  String getSignature() {
    return signature;
  }

  String getFooter() {
    return footer;
  }

  void update(String signature, String footer) {
    this.signature = signature;
    this.footer = footer;
    this.updatedAt = Instant.now();
  }
}
