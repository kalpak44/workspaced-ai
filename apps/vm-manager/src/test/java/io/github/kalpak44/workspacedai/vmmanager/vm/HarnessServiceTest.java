package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HarnessServiceTest {

  final VmRepository vms = new InMemoryVmRepository();
  final InMemorySecretStore store = new InMemorySecretStore();
  final VmSecretService secrets = new VmSecretService(vms, store);
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
    secrets.init(vm, "sk-or-v1-test", Map.of("OPENCODE_PASSWORD", "harness-pw"));
  }

  @Test
  void handsOverTheImageAndTheEnvironment() {
    var harness = new HarnessService(secrets, new HarnessProperties(" ghcr.io/h:1 ")).get(vm);

    assertThat(harness.image()).isEqualTo("ghcr.io/h:1");
    assertThat(harness.environment())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("OPENROUTER_API_KEY", "sk-or-v1-test", "OPENCODE_PASSWORD", "harness-pw"));
  }

  @Test
  void printsNoValue() {
    var harness = new HarnessService(secrets, new HarnessProperties("ghcr.io/h:1")).get(vm);

    assertThat(harness.toString())
        .contains("OPENROUTER_API_KEY")
        .doesNotContain("sk-or-v1-test")
        .doesNotContain("harness-pw");
  }

  @Test
  void refusesWithoutAnImageButNotBeforeKnowingTheVm() {
    var unconfigured = new HarnessService(secrets, new HarnessProperties(" "));

    assertThatThrownBy(() -> unconfigured.get(UUID.randomUUID()))
        .isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> unconfigured.get(vm))
        .isInstanceOf(HarnessNotConfiguredException.class);
    assertThatThrownBy(() -> new HarnessService(secrets, new HarnessProperties(null)).get(vm))
        .isInstanceOf(HarnessNotConfiguredException.class);
  }
}
