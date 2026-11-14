package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.UUID;

/** There is no VM with that id. */
public class VmNotFoundException extends RuntimeException {

  public VmNotFoundException(UUID id) {
    super("no VM " + id);
  }
}
