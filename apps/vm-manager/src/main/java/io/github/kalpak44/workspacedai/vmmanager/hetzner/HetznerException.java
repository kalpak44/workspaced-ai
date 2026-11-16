package io.github.kalpak44.workspacedai.vmmanager.hetzner;

/** Hetzner could not be asked, or did not answer as expected. */
public class HetznerException extends RuntimeException {

  // What Hetzner answered, or 0 when it never did.
  private final int status;

  public HetznerException(String message) {
    this(message, 0);
  }

  public HetznerException(String message, int status) {
    super(message);
    this.status = status;
  }

  public HetznerException(String message, Throwable cause) {
    super(message, cause);
    this.status = 0;
  }

  public int status() {
    return status;
  }

  /** No token is configured, so no request was made at all. */
  public static class NotConfigured extends HetznerException {

    public NotConfigured() {
      super("no Hetzner API token is configured");
    }
  }
}
