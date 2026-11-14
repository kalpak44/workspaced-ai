package io.github.kalpak44.workspacedai.vmmanager.vault;

/** Vault could not be asked, or did not answer as expected. */
public class VaultException extends RuntimeException {

  public VaultException(String message) {
    super(message);
  }

  public VaultException(String message, Throwable cause) {
    super(message, cause);
  }

  /** No Vault address is configured, so no request was made at all. */
  public static class NotConfigured extends VaultException {

    public NotConfigured() {
      super("no Vault address is configured");
    }
  }
}
