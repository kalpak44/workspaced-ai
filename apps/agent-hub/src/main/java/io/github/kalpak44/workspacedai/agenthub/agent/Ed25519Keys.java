package io.github.kalpak44.workspacedai.agenthub.agent;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/** Ed25519 with the JDK alone, from the raw 32-byte public keys the agents send. */
public final class Ed25519Keys {

  public static final int KEY_LENGTH = 32;

  // The DER SubjectPublicKeyInfo header for an Ed25519 key, which the JDK needs before the
  // raw key bytes.
  private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

  private Ed25519Keys() {}

  /**
   * @throws MalformedKeyException it is not a 32-byte key the JDK accepts
   */
  public static PublicKey decode(byte[] raw) {
    if (raw.length != KEY_LENGTH) {
      throw new MalformedKeyException("an Ed25519 public key is 32 bytes, not " + raw.length);
    }
    var encoded = new byte[X509_PREFIX.length + KEY_LENGTH];
    System.arraycopy(X509_PREFIX, 0, encoded, 0, X509_PREFIX.length);
    System.arraycopy(raw, 0, encoded, X509_PREFIX.length, KEY_LENGTH);
    try {
      return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    } catch (GeneralSecurityException e) {
      throw new MalformedKeyException("not an Ed25519 public key", e);
    }
  }

  /** False for any signature that does not verify, malformed ones included. */
  public static boolean verify(PublicKey key, byte[] message, byte[] signature) {
    try {
      var verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(message);
      return verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }
}
