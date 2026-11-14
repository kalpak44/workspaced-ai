package io.github.kalpak44.workspacedai.agenthub.agent;

/** A signed request that is not proven to come from the VM it names. */
public class SignatureRefusedException extends RuntimeException {

  public SignatureRefusedException(String message) {
    super(message);
  }
}
