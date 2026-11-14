package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The rules on their own: an in-memory store for Vault and one VM to keep secrets for. */
class VmSecretServiceTest {

  final VmRepository vms = new InMemoryVmRepository();
  final InMemorySecretStore store = new InMemorySecretStore();
  final VmSecretService service = new VmSecretService(vms, store);
  final UUID vm = UUID.randomUUID();

  @BeforeEach
  void vmWithItsKey() {
    vms.save(
        new Vm(
            vm,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "cx23",
            1,
            VmStatus.PROVISIONING,
            null,
            Instant.EPOCH,
            null));
    service.init(vm, "sk-or-v1-test");
  }

  @Test
  void listsTheSystemSecretFirstThenTheRestByName() {
    service.add(vm, "ZED", "z");
    service.add(vm, "ALPHA", "a");

    assertThat(service.list(vm))
        .containsExactly(
            new VmSecret("OPENROUTER_API_KEY", true),
            new VmSecret("ALPHA", false),
            new VmSecret("ZED", false));
  }

  @Test
  void keepsTheValueAndAddsBesideTheKey() {
    assertThat(service.add(vm, "GITHUB_TOKEN", "ghp_x"))
        .isEqualTo(new VmSecret("GITHUB_TOKEN", false));

    assertThat(store.entries.get(vm))
        .containsEntry("GITHUB_TOKEN", "ghp_x")
        .containsEntry("OPENROUTER_API_KEY", "sk-or-v1-test");
  }

  @Test
  void refusesASecretItAlreadyHas() {
    service.add(vm, "GITHUB_TOKEN", "one");

    assertThatThrownBy(() -> service.add(vm, "GITHUB_TOKEN", "two"))
        .isInstanceOf(SecretRefusedException.class)
        .hasMessageContaining("already has");
    assertThat(store.entries.get(vm)).containsEntry("GITHUB_TOKEN", "one");
  }

  @Test
  void neverReplacesOrRemovesTheSystemSecret() {
    assertThatThrownBy(() -> service.add(vm, "OPENROUTER_API_KEY", "other"))
        .isInstanceOf(SecretRefusedException.class);
    assertThatThrownBy(() -> service.remove(vm, "OPENROUTER_API_KEY"))
        .isInstanceOf(SecretRefusedException.class);
    assertThat(store.entries.get(vm)).containsEntry("OPENROUTER_API_KEY", "sk-or-v1-test");
  }

  @Test
  void holdsFiftyBesidesTheKeyAndNoMore() {
    for (var i = 0; i < VmSecretService.MAX_SECRETS; i++) {
      service.add(vm, "S" + i, "v");
    }

    assertThatThrownBy(() -> service.add(vm, "ONE_MORE", "v"))
        .isInstanceOf(SecretRefusedException.class)
        .hasMessageContaining("at most 50");
    assertThat(service.list(vm)).hasSize(VmSecretService.MAX_SECRETS + 1);

    service.remove(vm, "S0");
    service.add(vm, "ONE_MORE", "v");
  }

  @Test
  void removesOnlyWhatItHas() {
    service.add(vm, "GITHUB_TOKEN", "ghp_x");

    service.remove(vm, "GITHUB_TOKEN");

    assertThat(store.entries.get(vm)).doesNotContainKey("GITHUB_TOKEN");
    assertThatThrownBy(() -> service.remove(vm, "GITHUB_TOKEN"))
        .isInstanceOf(SecretNotFoundException.class);
  }

  @Test
  void answersNotFoundForAnUnknownVm() {
    var other = UUID.randomUUID();
    assertThatThrownBy(() -> service.list(other)).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> service.add(other, "A", "v")).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> service.remove(other, "A")).isInstanceOf(VmNotFoundException.class);
    assertThat(store.entries).containsOnlyKeys(vm);
  }
}
