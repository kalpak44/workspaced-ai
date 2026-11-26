package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.FakeHetznerClient;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class NoLoginSshKeyTest {

  @Test
  void uploadsAnOpenSshEd25519KeyOnceAndReusesItsId() {
    var hetzner = new FakeHetznerClient();
    var key = new NoLoginSshKey(hetzner);

    assertThat(key.id()).isEqualTo(key.id());

    var parts = hetzner.sshKeys.get(NoLoginSshKey.NAME).split(" ");
    assertThat(parts[0]).isEqualTo("ssh-ed25519");
    assertThat(parts[2]).isEqualTo(NoLoginSshKey.NAME);
    // A length-prefixed "ssh-ed25519", then a length-prefixed 32-byte key.
    var blob = Base64.getDecoder().decode(parts[1]);
    assertThat(blob).hasSize(4 + 11 + 4 + 32);
    assertThat(new String(blob, 4, 11, java.nio.charset.StandardCharsets.US_ASCII))
        .isEqualTo("ssh-ed25519");
  }
}
