package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import java.util.UUID;

/** The VM manager has no VM with that id, so it was deleted while its agent still runs. */
public class VmNotFoundException extends RuntimeException {

  public VmNotFoundException(UUID id) {
    super("no VM " + id);
  }
}
