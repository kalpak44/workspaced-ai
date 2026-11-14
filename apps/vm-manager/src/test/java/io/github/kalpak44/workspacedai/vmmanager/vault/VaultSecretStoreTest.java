package io.github.kalpak44.workspacedai.vmmanager.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.vmmanager.MutableClock;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP store against a local fake Vault, signing in as the pod would. */
class VaultSecretStoreTest {

  final FakeVault vault = new FakeVault();
  final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
  final UUID vm = UUID.randomUUID();
  final String key = "workspaced-ai/vms/" + vm;

  @TempDir Path dir;

  @AfterEach
  void stop() {
    vault.close();
  }

  VaultSecretStore store(String token) throws IOException {
    var jwt = Files.writeString(dir.resolve("token"), FakeVault.JWT + "\n");
    return new VaultSecretStore(
        new VaultProperties(
            URI.create(vault.url()),
            token,
            "kubernetes",
            "workspaced-ai-vm-manager",
            jwt,
            "secret",
            "workspaced-ai/vms",
            Duration.ofSeconds(5)),
        JsonMapper.builder().build(),
        clock);
  }

  @Test
  void signsInOnceWithItsServiceAccountAndKeepsTheToken() throws IOException {
    var store = store(null);

    store.update(vm, _ -> Map.of("OPENROUTER_API_KEY", "sk"));
    assertThat(store.names(vm)).containsExactly("OPENROUTER_API_KEY");

    assertThat(vault.logins).containsExactly("workspaced-ai-vm-manager");
    assertThat(vault.entries.get(key)).containsExactly(Map.entry("OPENROUTER_API_KEY", "sk"));
  }

  @Test
  void signsInAgainOnceTheLeaseIsMostlySpent() throws IOException {
    var store = store(null);
    store.names(vm);

    clock.advance(Duration.ofMinutes(50));
    store.names(vm);

    assertThat(vault.logins).hasSize(2);
  }

  @Test
  void signsInAgainWhenItsTokenIsRefused() throws IOException {
    var store = store(null);
    store.names(vm);
    vault.tokens.clear();

    store.update(vm, _ -> Map.of("A", "1"));

    assertThat(vault.logins).hasSize(2);
    assertThat(vault.entries.get(key)).containsEntry("A", "1");
  }

  @Test
  void usesAFixedTokenWithoutSigningIn() throws IOException {
    var store = store(FakeVault.ROOT_TOKEN);

    store.update(vm, _ -> Map.of("A", "1"));

    assertThat(vault.logins).isEmpty();
    assertThat(store.names(vm)).containsExactly("A");
  }

  @Test
  void keepsOnlyTheLatestVersionOfANewEntry() throws IOException {
    store(null).update(vm, _ -> Map.of("A", "1"));

    assertThat(vault.maxVersions).containsEntry(key, 1);
  }

  @Test
  void runsTheChangeAgainOnTopOfAWriteThatLandedInBetween() throws IOException {
    var store = store(null);
    store.update(vm, _ -> Map.of("A", "1"));
    vault.beforeWrite =
        () -> {
          vault.beforeWrite = () -> {};
          vault.put(key, Map.of("A", "1", "B", "2"));
        };

    store.update(
        vm,
        current -> {
          var next = new HashMap<>(current);
          next.put("C", "3");
          return next;
        });

    assertThat(vault.entries.get(key)).containsOnlyKeys("A", "B", "C");
  }

  @Test
  void deletesTheEntryAndItsHistory() throws IOException {
    var store = store(null);
    store.update(vm, _ -> Map.of("A", "1"));

    store.deleteAll(vm);
    store.deleteAll(vm);

    assertThat(vault.entries).doesNotContainKey(key);
    assertThat(store.names(vm)).isEmpty();
  }

  @Test
  void failsWhenItCannotSignIn() throws IOException {
    Files.writeString(dir.resolve("token"), "wrong");
    var store =
        new VaultSecretStore(
            new VaultProperties(
                URI.create(vault.url()),
                null,
                "kubernetes",
                "workspaced-ai-vm-manager",
                dir.resolve("token"),
                "secret",
                "workspaced-ai/vms",
                Duration.ofSeconds(5)),
            JsonMapper.builder().build(),
            clock);

    assertThatThrownBy(() -> store.names(vm))
        .isInstanceOf(VaultException.class)
        .hasMessageContaining("403");
  }

  @Test
  void failsWithoutAnAddress() {
    var store =
        new VaultSecretStore(
            new VaultProperties(
                null,
                null,
                "kubernetes",
                "r",
                dir.resolve("none"),
                "secret",
                "p",
                Duration.ofSeconds(1)),
            JsonMapper.builder().build(),
            clock);

    assertThatThrownBy(() -> store.names(vm)).isInstanceOf(VaultException.NotConfigured.class);
  }
}
