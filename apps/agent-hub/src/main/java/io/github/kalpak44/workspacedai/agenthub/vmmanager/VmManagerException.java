package io.github.kalpak44.workspacedai.agenthub.vmmanager;

/** The VM manager could not be asked, or did not answer as expected. */
public class VmManagerException extends RuntimeException {

  public VmManagerException(String message) {
    super(message);
  }

  public VmManagerException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * It answered 503: it lacks something its deployment has to provide, which a retry later may
   * find, rather than having failed.
   */
  public static class Unavailable extends VmManagerException {

    public Unavailable(String message) {
      super(message);
    }
  }
}
