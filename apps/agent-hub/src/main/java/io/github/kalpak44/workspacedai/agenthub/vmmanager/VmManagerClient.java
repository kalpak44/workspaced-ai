package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import java.util.UUID;

/** The VM manager as this app uses it: the two reports a VM's agent makes about itself. */
public interface VmManagerClient {

  /**
   * @throws VmNotFoundException the VM manager has no such VM
   * @throws VmManagerException it could not be asked, or did not answer as expected
   */
  void ready(UUID vmId);

  /**
   * @throws VmNotFoundException the VM manager has no such VM
   * @throws VmManagerException it could not be asked, or did not answer as expected
   */
  void heartbeat(UUID vmId);
}
