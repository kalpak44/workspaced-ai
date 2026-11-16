package io.github.kalpak44.workspacedai.vmmanager.vm;

/** A rule Hetzner would refuse, or more of them than a VM may have. */
public class FirewallRuleRefusedException extends RuntimeException {

  public FirewallRuleRefusedException(String message) {
    super(message);
  }
}
