package io.github.kalpak44.workspacedai.vmmanager.hetzner;

/** Hetzner could not be asked, or did not answer as expected. */
public class HetznerException extends RuntimeException {

  public HetznerException(String message) {
    super(message);
  }

  public HetznerException(String message, Throwable cause) {
    super(message, cause);
  }

  /** No token is configured, so no request was made at all. */
  public static class NotConfigured extends HetznerException {

    public NotConfigured() {
      super("no Hetzner API token is configured");
    }
  }
}
