package io.github.kalpak44.workspacedai.agenthub.agent;

/** The public key is not base64 of a 32-byte Ed25519 key. */
public class MalformedKeyException extends RuntimeException {

  public MalformedKeyException(String message) {
    super(message);
  }

  public MalformedKeyException(String message, Throwable cause) {
    super(message, cause);
  }
}
