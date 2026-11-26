package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * An SSH key whose private half is never kept, installed on every VM no other key is configured
 * for. A server created with no key gets a root password, which Hetzner emails to the account owner
 * for every VM; SSH is off on the image, so the key opens nothing.
 */
@Component
public class NoLoginSshKey {

  static final String NAME = "workspaced-ai-no-login";
  // A DER-encoded Ed25519 public key is this prefix and then the 32 bytes OpenSSH wants.
  private static final int DER_PREFIX = 12;

  private final HetznerClient hetzner;
  private volatile Long id;

  public NoLoginSshKey(HetznerClient hetzner) {
    this.hetzner = hetzner;
  }

  /** Its id in the project, uploaded on first use. */
  long id() {
    var known = id;
    if (known == null) {
      known = hetzner.sshKey(NAME, NoLoginSshKey::publicKey);
      id = known;
    }
    return known;
  }

  static String publicKey() {
    byte[] encoded;
    try {
      encoded = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 is not available", e);
    }
    var blob = new ByteArrayOutputStream();
    field(blob, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
    field(blob, Arrays.copyOfRange(encoded, DER_PREFIX, encoded.length));
    return "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob.toByteArray()) + " " + NAME;
  }

  private static void field(ByteArrayOutputStream out, byte[] value) {
    out.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());
    out.writeBytes(value);
  }
}
