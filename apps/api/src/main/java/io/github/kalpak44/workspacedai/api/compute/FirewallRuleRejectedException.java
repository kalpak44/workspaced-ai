package io.github.kalpak44.workspacedai.api.compute;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 400 carrying the VM manager's own reason, which names what is wrong with the rule. */
public class FirewallRuleRejectedException extends ResponseStatusException {

  public FirewallRuleRejectedException(String reason) {
    super(HttpStatus.BAD_REQUEST, reason);
  }
}
