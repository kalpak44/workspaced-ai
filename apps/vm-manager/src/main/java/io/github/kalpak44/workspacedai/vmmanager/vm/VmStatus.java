package io.github.kalpak44.workspacedai.vmmanager.vm;

/** Where a VM stands, as far as the manager can tell. */
public enum VmStatus {
  /** Requested from Hetzner, or booting, and has not reported ready yet. */
  PROVISIONING,
  /** Booted and reported ready. */
  READY,
  /** Being rebooted; reports ready again once it is back. */
  RESTARTING
}
