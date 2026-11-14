package io.github.kalpak44.workspacedai.agenthub.agent;

import java.util.UUID;

/** The VM already has an enrolled agent; a second key would let a copy of it act as the VM. */
public class AlreadyEnrolledException extends RuntimeException {

  public AlreadyEnrolledException(UUID vmId) {
    super("VM " + vmId + " is already enrolled");
  }
}
