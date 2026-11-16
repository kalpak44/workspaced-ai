package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.Firewall;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.FirewallRule;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Each VM's own Cloud Firewall: made with no rules when the VM is created, so nothing reaches it
 * until a rule lets it in, and found again by its vm-id label. Hetzner refuses to delete a firewall
 * while it still applies to a server, which lasts a while after the server is deleted, so a sweep
 * deletes the ones whose VM is gone instead of the VM's delete.
 */
@Service
public class VmFirewallService {

  /** Well under Hetzner's own cap, which counts each address family of a rule separately. */
  static final int MAX_RULES = 50;

  static final int MAX_SOURCES = 20;
  static final List<String> ANYWHERE = List.of("0.0.0.0/0", "::/0");
  private static final Set<String> PROTOCOLS = Set.of("tcp", "udp", "icmp");
  private static final Pattern PORT = Pattern.compile("(\\d{1,5})(?:-(\\d{1,5}))?");
  // A firewall is made just before its server; younger than this, its VM may not be saved yet.
  private static final Duration GRACE = Duration.ofMinutes(10);

  private static final Logger log = LoggerFactory.getLogger(VmFirewallService.class);

  private final VmRepository vms;
  private final HetznerClient hetzner;
  private final VmInventory inventory;
  private final Clock clock;

  public VmFirewallService(
      VmRepository vms, HetznerClient hetzner, VmInventory inventory, Clock clock) {
    this.vms = vms;
    this.hetzner = hetzner;
    this.inventory = inventory;
    this.clock = clock;
  }

  /** Made before the server it protects, so the server is behind it from its first boot. */
  long create(UUID vmId, UUID workspaceId) {
    return hetzner.createFirewall(
        "vm-" + vmId,
        Map.of(
            HetznerVmInventory.MANAGED_BY, HetznerVmInventory.MANAGER,
            HetznerVmInventory.VM_ID, vmId.toString(),
            HetznerVmInventory.WORKSPACE_ID, workspaceId.toString()));
  }

  public List<FirewallRule> rules(UUID vmId) {
    return firewall(vmId).rules();
  }

  /**
   * Replaces every rule. A rule with no sources lets in anyone, over IPv4 and IPv6.
   *
   * @throws FirewallRuleRefusedException when a rule is one Hetzner would refuse
   */
  public List<FirewallRule> setRules(UUID vmId, List<FirewallRule> rules) {
    var valid = validate(rules);
    var firewall = firewall(vmId);
    hetzner.setFirewallRules(firewall.id(), valid);
    return valid;
  }

  private Firewall firewall(UUID vmId) {
    if (vms.findById(vmId).isEmpty()) {
      throw new VmNotFoundException(vmId);
    }
    return hetzner
        .firewalls(
            HetznerVmInventory.MANAGED_BY
                + "="
                + HetznerVmInventory.MANAGER
                + ","
                + HetznerVmInventory.VM_ID
                + "="
                + vmId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new FirewallMissingException(vmId));
  }

  /** Only once the inventory is loaded, or every firewall would look like its VM is gone. */
  @Scheduled(fixedDelay = 10, initialDelay = 10, timeUnit = TimeUnit.MINUTES)
  void deleteOrphans() {
    if (!inventory.isLoaded()) {
      return;
    }
    List<Firewall> firewalls;
    try {
      firewalls =
          hetzner.firewalls(HetznerVmInventory.MANAGED_BY + "=" + HetznerVmInventory.MANAGER);
    } catch (HetznerException e) {
      log.warn("could not list VM firewalls: {}", e.getMessage());
      return;
    }
    var cutoff = clock.instant().minus(GRACE);
    for (var firewall : firewalls) {
      if (firewall.servers() > 0 || firewall.created().isAfter(cutoff) || hasVm(firewall)) {
        continue;
      }
      try {
        hetzner.deleteFirewall(firewall.id());
        log.info("deleted firewall {} of a VM that is gone", firewall.name());
      } catch (HetznerException e) {
        log.warn("could not delete firewall {}: {}", firewall.name(), e.getMessage());
      }
    }
  }

  private boolean hasVm(Firewall firewall) {
    try {
      return vms.findById(UUID.fromString(firewall.labels().get(HetznerVmInventory.VM_ID)))
          .isPresent();
    } catch (IllegalArgumentException | NullPointerException e) {
      // Not one this app labelled; leave it alone.
      return true;
    }
  }

  static List<FirewallRule> validate(List<FirewallRule> rules) {
    if (rules.size() > MAX_RULES) {
      throw new FirewallRuleRefusedException("a VM may have at most " + MAX_RULES + " rules");
    }
    var valid = new ArrayList<FirewallRule>();
    for (var rule : rules) {
      var protocol = rule.protocol() == null ? "" : rule.protocol().strip().toLowerCase();
      if (!PROTOCOLS.contains(protocol)) {
        throw new FirewallRuleRefusedException("protocol must be tcp, udp or icmp");
      }
      var port = port(protocol, rule.port());
      var sources = new LinkedHashSet<String>();
      for (var source : rule.sourceIps() == null ? List.<String>of() : rule.sourceIps()) {
        sources.add(network(source));
      }
      if (sources.size() > MAX_SOURCES) {
        throw new FirewallRuleRefusedException(
            "a rule may let in at most " + MAX_SOURCES + " networks");
      }
      var description =
          rule.description() == null || rule.description().isBlank()
              ? null
              : rule.description().strip();
      if (description != null && description.length() > 255) {
        throw new FirewallRuleRefusedException("a description has at most 255 characters");
      }
      valid.add(
          new FirewallRule(
              protocol, port, sources.isEmpty() ? ANYWHERE : List.copyOf(sources), description));
    }
    return List.copyOf(valid);
  }

  // icmp has no ports, and Hetzner refuses one; tcp and udp need one.
  private static String port(String protocol, String port) {
    var blank = port == null || port.isBlank();
    if (protocol.equals("icmp")) {
      if (!blank) {
        throw new FirewallRuleRefusedException("an icmp rule has no port");
      }
      return null;
    }
    var matcher = blank ? null : PORT.matcher(port.strip());
    if (matcher == null || !matcher.matches()) {
      throw new FirewallRuleRefusedException(
          "a " + protocol + " rule needs a port, e.g. 80, or a range, e.g. 8000-8100");
    }
    var start = Integer.parseInt(matcher.group(1));
    var end = matcher.group(2) == null ? start : Integer.parseInt(matcher.group(2));
    if (start < 1 || end > 65535 || (matcher.group(2) != null && start >= end)) {
      throw new FirewallRuleRefusedException(
          "ports run from 1 to 65535, and a range starts below where it ends");
    }
    return matcher.group(2) == null ? Integer.toString(start) : start + "-" + end;
  }

  /**
   * A network in CIDR notation; a bare address means that one address. Hetzner refuses a network
   * with host bits set, so 10.0.0.1/24 is refused here rather than rounded down to what was not
   * asked for.
   */
  static String network(String source) {
    var text = source == null ? "" : source.strip();
    var slash = text.indexOf('/');
    var address = slash < 0 ? text : text.substring(0, slash);
    InetAddress parsed;
    try {
      parsed = InetAddress.ofLiteral(address);
    } catch (IllegalArgumentException e) {
      throw new FirewallRuleRefusedException(source + " is not an IP address or network");
    }
    var bytes = parsed.getAddress();
    var bits = bytes.length * 8;
    int prefix;
    try {
      prefix = slash < 0 ? bits : Integer.parseInt(text.substring(slash + 1));
    } catch (NumberFormatException e) {
      throw new FirewallRuleRefusedException(source + " is not an IP address or network");
    }
    if (prefix < 0 || prefix > bits) {
      throw new FirewallRuleRefusedException(source + " has a prefix longer than its address");
    }
    for (var bit = prefix; bit < bits; bit++) {
      if (((bytes[bit / 8] & 0xff) & (0x80 >> (bit % 8))) != 0) {
        throw new FirewallRuleRefusedException(
            source + " has host bits set; give the network it starts at");
      }
    }
    return parsed.getHostAddress() + "/" + prefix;
  }
}
