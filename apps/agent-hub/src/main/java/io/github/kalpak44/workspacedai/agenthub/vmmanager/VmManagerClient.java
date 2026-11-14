package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import java.util.UUID;

/** The VM manager as this app uses it: what a VM's agent reports about itself, and asks for. */
public interface VmManagerClient {

  /**
   * @throws VmNotFoundException the VM manager has no such VM
   * @throws VmManagerException it could not be asked, or did not answer as expected
   */
  void ready(UUID vmId);

  /**
   * @return the VM's status as the VM manager now has it, e.g. RESTARTING, which is how the VM
   *     learns its workspace was restarted
   * @throws VmNotFoundException the VM manager has no such VM
   * @throws VmManagerException it could not be asked, or did not answer as expected
   */
  String heartbeat(UUID vmId);

  /**
   * @return the VM manager's JSON body as it came, image and environment; it holds secret values,
   *     so it is never parsed, logged or kept
   * @throws VmNotFoundException the VM manager has no such VM
   * @throws VmManagerException.Unavailable it has no harness to hand out, or no Vault
   * @throws VmManagerException it could not be asked, or did not answer as expected
   */
  byte[] harness(UUID vmId);
}
