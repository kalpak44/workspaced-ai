package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.UUID;

/** The VM has no firewall of its own, so it has no rules to read or set. */
public class FirewallMissingException extends RuntimeException {

  public FirewallMissingException(UUID id) {
    super("VM " + id + " has no firewall of its own; recreate it to get one");
  }
}
