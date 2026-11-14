package io.github.kalpak44.workspacedai.vmmanager.vm;

/** The change would add a secret the VM already has, pass its limit, or touch its system secret. */
public class SecretRefusedException extends RuntimeException {

  public SecretRefusedException(String message) {
    super(message);
  }
}
