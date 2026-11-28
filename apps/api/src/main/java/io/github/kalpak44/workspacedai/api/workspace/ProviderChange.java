package io.github.kalpak44.workspacedai.api.workspace;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Durable restart intent. Keys are sealed; never serialize this persistence object. */
@Embeddable
public class ProviderChange {
  @Column(name = "pending_connector_id")
  public UUID pending;

  @Column(name = "provider_change_phase", nullable = false)
  public String phase = "NONE";

  @Column(name = "provider_revision", nullable = false)
  public long revision;

  @Column(name = "provider_change_deadline")
  public Instant deadline;

  @Column(name = "provider_target_key")
  public String targetKey;

  @Column(name = "provider_previous_key")
  public String previousKey;

  @Column(name = "provider_change_error")
  public String error;

  @Column(name = "provider_start_config_revision", nullable = false)
  public long configurationRevision;

  public boolean inFlight() {
    return phase.equals("APPLYING") || phase.equals("ROLLING_BACK");
  }

  public boolean blocked() {
    return inFlight() || phase.equals("BLOCKED");
  }
}
