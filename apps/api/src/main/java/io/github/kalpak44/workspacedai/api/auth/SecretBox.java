package io.github.kalpak44.workspacedai.api.auth;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Optional;

/**
 * Encrypts short values with AES-GCM under a key derived from the secret every instance shares.
 * Each use names a label of its own, so no two share a key and none is the key the session tokens
 * are signed with.
 */
final class SecretBox {

  private final byte[] key;

  SecretBox(String secret, String label) {
    this.key = sha256(label + ":" + secret);
  }

  String seal(String value) {
    var jwe =
        new JWEObject(
            new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256GCM), new Payload(value));
    try {
      jwe.encrypt(new DirectEncrypter(key));
    } catch (JOSEException e) {
      throw new IllegalStateException("cannot encrypt", e);
    }
    return jwe.serialize();
  }

  /** Empty for anything sealed under another key or label, or altered. */
  Optional<String> open(String sealed) {
    try {
      var jwe = JWEObject.parse(sealed);
      jwe.decrypt(new DirectDecrypter(key));
      return Optional.of(jwe.getPayload().toString());
    } catch (ParseException | JOSEException _) {
      return Optional.empty();
    }
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
