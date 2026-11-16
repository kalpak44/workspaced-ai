package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.kalpak44.workspacedai.vmmanager.cloudflare.CloudflareException;
import io.github.kalpak44.workspacedai.vmmanager.cloudflare.DnsRecord;
import io.github.kalpak44.workspacedai.vmmanager.cloudflare.FakeCloudflareDns;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VmDnsServiceTest {

  final VmRepository vms = new InMemoryVmRepository();
  final FakeCloudflareDns dns = new FakeCloudflareDns();
  boolean inventoryLoaded = true;
  final VmDnsService service =
      new VmDnsService(vms, dns, () -> inventoryLoaded, new VmDnsProperties("vm.example.com", 60));

  Vm vm(String ipv4, String ipv6) {
    return vms.save(
        new Vm(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "cx23",
            1,
            VmStatus.READY,
            ipv4,
            ipv6,
            Instant.EPOCH,
            null));
  }

  String name(Vm vm) {
    return vm.id() + ".vm.example.com";
  }

  @Test
  void publishesEachVmUnderItsOwnName() {
    var vm = vm("203.0.113.1", "2001:db8:0:1:0:0:0:1");

    service.sync();

    assertThat(dns.records.values())
        .extracting(DnsRecord::type, DnsRecord::name, DnsRecord::content)
        .containsExactlyInAnyOrder(
            tuple("A", name(vm), "203.0.113.1"), tuple("AAAA", name(vm), "2001:db8:0:1:0:0:0:1"));
  }

  @Test
  void publishesOnlyTheAddressesAVmHas() {
    var vm = vm("203.0.113.1", null);

    service.sync();

    assertThat(dns.writes).containsExactly("create A " + name(vm));
  }

  @Test
  void leavesARecordAloneOnceItMatches() {
    var vm = vm("203.0.113.1", "2001:db8:0:1:0:0:0:1");
    // Cloudflare keeps IPv6 compressed, which reads differently but is the same address.
    dns.add("A", name(vm), "203.0.113.1");
    dns.add("AAAA", name(vm), "2001:db8:0:1::1");

    service.sync();

    assertThat(dns.writes).isEmpty();
  }

  @Test
  void pointsANameThatDriftedBackAtItsVm() {
    var vm = vm("203.0.113.1", null);
    var id = dns.add("A", name(vm), "198.51.100.9");

    service.sync();

    assertThat(dns.records.get(id).content()).isEqualTo("203.0.113.1");
  }

  @Test
  void deletesTheNamesOfVmsThatAreGoneAndAnyDuplicate() {
    var vm = vm("203.0.113.1", null);
    dns.add("A", name(vm), "203.0.113.1");
    dns.add("A", name(vm), "203.0.113.1");
    dns.add("A", UUID.randomUUID() + ".vm.example.com", "203.0.113.2");

    service.sync();

    assertThat(dns.records.values()).extracting(DnsRecord::name).containsExactly(name(vm));
  }

  @Test
  void neverTouchesARecordOfAnotherShape() {
    var id = UUID.randomUUID();
    dns.add("A", "ai.example.com", "203.0.113.5");
    dns.add("A", "www.vm.example.com", "203.0.113.5");
    dns.add("TXT", id + ".vm.example.com", "verification");
    dns.add("A", "x." + id + ".vm.example.com", "203.0.113.5");

    service.sync();

    assertThat(dns.writes).isEmpty();
    assertThat(dns.records).hasSize(4);
  }

  @Test
  void keepsGoingPastOneFailedWrite() {
    var broken = vm("203.0.113.1", null);
    var fine = vm("203.0.113.2", null);
    dns.failingName = name(broken);

    service.sync();

    assertThat(dns.writes).containsExactly("create A " + name(fine));
  }

  @Test
  void waitsWhenCloudflareCannotList() {
    vm("203.0.113.1", null);
    dns.failure = new CloudflareException("Cloudflare answered 503");

    service.sync();

    assertThat(dns.records).isEmpty();
  }

  @Test
  void doesNothingBeforeTheInventoryIsLoaded() {
    vm("203.0.113.1", null);
    dns.add("A", UUID.randomUUID() + ".vm.example.com", "203.0.113.2");
    inventoryLoaded = false;

    service.sync();

    assertThat(dns.writes).isEmpty();
  }

  @Test
  void namesAVmOnlyWhenAZoneIsConfigured() {
    var vm = vm("203.0.113.1", null);

    assertThat(service.hostname(vm.id())).contains(name(vm));

    dns.configured = false;
    service.sync();
    assertThat(service.hostname(vm.id())).isEmpty();
    assertThat(dns.writes).isEmpty();
  }
}
