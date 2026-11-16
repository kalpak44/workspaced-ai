package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.cloudflare.CloudflareDns;
import io.github.kalpak44.workspacedai.vmmanager.cloudflare.CloudflareException;
import io.github.kalpak44.workspacedai.vmmanager.cloudflare.DnsRecord;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Publishes each VM as {@code <vm id>.<domain>}, A and AAAA straight to its addresses. A domain a
 * user connects points a CNAME here rather than at an address, so deleting the VM deletes the name
 * and the user's record resolves to nothing instead of to whoever gets the address next. A sweep
 * makes the zone match the VMs, so a failed or missed write is repaired on the next pass.
 */
@Service
public class VmDnsService {

  private static final Set<String> TYPES = Set.of("A", "AAAA");

  private static final Logger log = LoggerFactory.getLogger(VmDnsService.class);

  private final VmRepository vms;
  private final CloudflareDns dns;
  private final VmInventory inventory;
  private final VmDnsProperties properties;

  public VmDnsService(
      VmRepository vms, CloudflareDns dns, VmInventory inventory, VmDnsProperties properties) {
    this.vms = vms;
    this.dns = dns;
    this.inventory = inventory;
    this.properties = properties;
  }

  /** Empty when no zone is configured, since the name would then resolve to nothing. */
  public Optional<String> hostname(UUID vmId) {
    return dns.isConfigured() ? Optional.of(vmId + "." + properties.domain()) : Optional.empty();
  }

  /** Only once the inventory is loaded, or every VM's name would look like its VM is gone. */
  @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
  synchronized void sync() {
    if (!dns.isConfigured() || !inventory.isLoaded()) {
      return;
    }
    List<DnsRecord> records;
    try {
      records = dns.records("." + properties.domain());
    } catch (CloudflareException e) {
      log.warn("could not list VM names: {}", e.getMessage());
      return;
    }
    // Read after the records, so a VM created in between only waits for the next pass rather
    // than having its new name deleted.
    var wanted = wanted();
    var kept = new HashSet<Key>();
    for (var record : records) {
      if (!owned(record)) {
        continue;
      }
      var key = new Key(record.type(), record.name());
      var address = wanted.get(key);
      if (address == null || !kept.add(key)) {
        attempt("delete " + record.name(), () -> dns.delete(record.id()));
      } else if (!sameAddress(address, record.content())) {
        attempt("update " + record.name(), () -> dns.update(record.id(), address));
      }
    }
    wanted.forEach(
        (key, address) -> {
          if (!kept.contains(key)) {
            attempt(
                "create " + key.name(),
                () -> dns.create(key.type(), key.name(), address, properties.ttl()));
          }
        });
  }

  private Map<Key, String> wanted() {
    var wanted = new HashMap<Key, String>();
    for (var vm : vms.findAll()) {
      var name = vm.id() + "." + properties.domain();
      if (vm.ipv4() != null) {
        wanted.put(new Key("A", name), vm.ipv4());
      }
      if (vm.ipv6() != null) {
        wanted.put(new Key("AAAA", name), vm.ipv6());
      }
    }
    return wanted;
  }

  // Only <uuid>.<domain> A and AAAA records, so a misconfigured domain can never reach a record
  // this app did not make.
  private boolean owned(DnsRecord record) {
    var suffix = "." + properties.domain();
    if (!TYPES.contains(record.type()) || !record.name().endsWith(suffix)) {
      return false;
    }
    var label = record.name().substring(0, record.name().length() - suffix.length());
    try {
      return UUID.fromString(label).toString().equals(label);
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  // Cloudflare stores an IPv6 address compressed, which Java never writes.
  private static boolean sameAddress(String a, String b) {
    try {
      return InetAddress.ofLiteral(a).equals(InetAddress.ofLiteral(b));
    } catch (IllegalArgumentException _) {
      return a.equals(b);
    }
  }

  // One failed write must not stop the others; the next pass retries it.
  private static void attempt(String what, Runnable write) {
    try {
      write.run();
    } catch (CloudflareException e) {
      log.warn("could not {}: {}", what, e.getMessage());
    }
  }

  private record Key(String type, String name) {}
}
