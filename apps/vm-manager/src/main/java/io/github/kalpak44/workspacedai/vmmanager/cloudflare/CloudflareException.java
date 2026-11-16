package io.github.kalpak44.workspacedai.vmmanager.cloudflare;

/** Cloudflare could not be asked, or did not answer as expected. */
public class CloudflareException extends RuntimeException {

  public CloudflareException(String message) {
    super(message);
  }

  public CloudflareException(String message, Throwable cause) {
    super(message, cause);
  }

  /** No token or zone is configured, so no request was made at all. */
  public static class NotConfigured extends CloudflareException {

    public NotConfigured() {
      super("no Cloudflare token and zone are configured");
    }
  }
}
