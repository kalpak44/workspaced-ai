package io.github.kalpak44.workspacedai.agenthub.agent;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Hashes {

  private Hashes() {}

  static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      // Every JDK is required to ship SHA-256.
      throw new IllegalStateException(e);
    }
  }
}
