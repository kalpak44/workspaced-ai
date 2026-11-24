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

/** One move of an account's guided tour, appended and never changed. */
@Entity
@Table(name = "tour_event")
public class TourEventEntity {

  /** What happened at the step. */
  public enum Kind {
    REACHED,
    PAUSED,
    RESUMED,
    /** Finished or quit: the tour ended at this step. */
    ENDED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  // The web app's step number, so renumbering the tour mixes two steps in one count.
  @Column(nullable = false, updatable = false)
  private int step;

  @Enumerated(EnumType.STRING)
  @Column(name = "event", nullable = false, length = 10, updatable = false)
  private Kind kind;

  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  protected TourEventEntity() {
    // For JPA.
  }

  TourEventEntity(UUID userId, int step, Kind kind) {
    this.userId = userId;
    this.step = step;
    this.kind = kind;
  }

  public UUID getUserId() {
    return userId;
  }

  public int getStep() {
    return step;
  }

  public Kind getKind() {
    return kind;
  }
}
