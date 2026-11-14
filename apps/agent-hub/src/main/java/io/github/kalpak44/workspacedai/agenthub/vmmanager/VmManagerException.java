package io.github.kalpak44.workspacedai.agenthub.vmmanager;

/** The VM manager could not be asked, or did not answer as expected. */
public class VmManagerException extends RuntimeException {

  public VmManagerException(String message) {
    super(message);
  }

  public VmManagerException(String message, Throwable cause) {
    super(message, cause);
  }
}
