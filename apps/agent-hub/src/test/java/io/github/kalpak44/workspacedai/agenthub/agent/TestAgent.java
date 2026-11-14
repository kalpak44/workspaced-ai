package io.github.kalpak44.workspacedai.agenthub.agent;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** A VM's agent as the supervisor is: its own Ed25519 key pair, signing as the protocol says. */
public final class TestAgent {

  private final KeyPair keys;

  public TestAgent() {
    try {
      keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The raw 32-byte key: the last 32 bytes of its X.509 encoding. */
  public byte[] rawPublicKey() {
    var encoded = keys.getPublic().getEncoded();
    return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
  }

  public String publicKey() {
    return Base64.getEncoder().encodeToString(rawPublicKey());
  }

  public String sign(String method, String path, long timestamp, byte[] body) {
    try {
      var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
      var message = method + "\n" + path + "\n" + timestamp + "\n" + hash;
      var signer = Signature.getInstance("Ed25519");
      signer.initSign(keys.getPrivate());
      signer.update(message.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(signer.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
