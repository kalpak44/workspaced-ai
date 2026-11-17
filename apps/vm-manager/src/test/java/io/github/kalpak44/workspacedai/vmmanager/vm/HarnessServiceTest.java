package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.vmmanager.cloudflare.FakeCloudflareDns;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HarnessServiceTest {

  final VmRepository vms = new InMemoryVmRepository();
  final InMemorySecretStore store = new InMemorySecretStore();
  final VmSecretService secrets = new VmSecretService(vms, store);
  final FakeCloudflareDns cloudflare = new FakeCloudflareDns();
  final VmDnsService dns =
      new VmDnsService(vms, cloudflare, () -> true, new VmDnsProperties("vm.example.com", 60));
  final UUID vm = UUID.randomUUID();

  HarnessService service(String image) {
    return new HarnessService(secrets, vms, dns, new HarnessProperties(image));
  }

  void saveVm(String ipv4) {
    vms.save(
        new Vm(
            vm,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "cx23",
            1,
            VmStatus.PROVISIONING,
            ipv4,
            null,
            Instant.EPOCH,
            null));
  }

  @BeforeEach
  void vmWithItsKey() {
    saveVm("203.0.113.10");
    secrets.init(vm, "sk-or-v1-test", Map.of("OPENCODE_PASSWORD", "harness-pw"));
  }

  @Test
  void handsOverTheImageAndTheEnvironment() {
    var harness = service(" ghcr.io/h:1 ").get(vm);

    assertThat(harness.image()).isEqualTo("ghcr.io/h:1");
    assertThat(harness.environment())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "OPENROUTER_API_KEY", "sk-or-v1-test",
                "OPENCODE_PASSWORD", "harness-pw",
                "WORKSPACE_HOSTNAME", vm + ".vm.example.com",
                "WORKSPACE_IP", "203.0.113.10"));
  }

  @Test
  void theVmsNameAndAddressWinOverASecretOfTheSameName() {
    secrets.add(vm, "WORKSPACE_HOSTNAME", "elsewhere.example.org");
    secrets.add(vm, "WORKSPACE_IP", "198.51.100.1");

    assertThat(service("ghcr.io/h:1").get(vm).environment())
        .containsEntry("WORKSPACE_HOSTNAME", vm + ".vm.example.com")
        .containsEntry("WORKSPACE_IP", "203.0.113.10");
  }

  @Test
  void leavesOutANameThatWouldResolveToNothingAndAnAddressNotYetAssigned() {
    cloudflare.configured = false;
    saveVm(null);

    assertThat(service("ghcr.io/h:1").get(vm).environment())
        .doesNotContainKeys("WORKSPACE_HOSTNAME", "WORKSPACE_IP");
  }

  @Test
  void printsNoValue() {
    var harness = service("ghcr.io/h:1").get(vm);

    assertThat(harness.toString())
        .contains("OPENROUTER_API_KEY")
        .doesNotContain("sk-or-v1-test")
        .doesNotContain("harness-pw");
  }

  @Test
  void refusesWithoutAnImageButNotBeforeKnowingTheVm() {
    var unconfigured = service(" ");

    assertThatThrownBy(() -> unconfigured.get(UUID.randomUUID()))
        .isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> unconfigured.get(vm))
        .isInstanceOf(HarnessNotConfiguredException.class);
    assertThatThrownBy(() -> service(null).get(vm))
        .isInstanceOf(HarnessNotConfiguredException.class);
  }
}
