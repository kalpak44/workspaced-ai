package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.UUID;

/** The VM has no secret of that name. */
public class SecretNotFoundException extends RuntimeException {

  public SecretNotFoundException(UUID vmId, String name) {
    super("VM " + vmId + " has no secret " + name);
  }
}
