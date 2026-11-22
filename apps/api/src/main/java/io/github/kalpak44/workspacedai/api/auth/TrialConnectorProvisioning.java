package io.github.kalpak44.workspacedai.api.auth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TrialConnectorProvisioning {
  private final TrialConnectors trials;

  public TrialConnectorProvisioning(TrialConnectors trials) {
    this.trials = trials;
  }

  @TransactionalEventListener
  public void created(UserCreated event) {
    trials.tryEnsure(event.userId());
  }
}
