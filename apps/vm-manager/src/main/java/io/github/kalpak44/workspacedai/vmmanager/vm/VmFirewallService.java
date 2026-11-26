package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.Firewall;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.FirewallRule;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import io.github.kalpak44.workspacedai.vmmanager.vm.SecretStore.Entry;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * A VM's inbound rules, kept in one of two places by the image it booted from. One that filters its
 * own traffic keeps them in Vault, applies what each heartbeat hands its supervisor, and sits
 * behind a shared Cloud Firewall that lets everything in and keeps outbound SMTP closed. An older
 * one has a Cloud Firewall of its own, made with no inbound rules and found again by its vm-id
 * label, which caps the VMs at Hetzner's limit on firewalls. Hetzner refuses to delete a firewall
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
  static final String ROLE = "role";
  static final String SHARED = "shared";
  // Hetzner documents no cap on the servers one firewall applies to; this stays well clear of one.
  static final int SHARED_SERVERS = 100;
  // The VM drops what its own rules do not let in, so the shared firewall lets everything through.
  static final List<FirewallRule> OPEN =
      List.of(
          new FirewallRule("tcp", "1-65535", ANYWHERE, null),
          new FirewallRule("udp", "1-65535", ANYWHERE, null),
          new FirewallRule("icmp", null, ANYWHERE, null));
  private static final String RULES = "rules";

  private static final Logger log = LoggerFactory.getLogger(VmFirewallService.class);

  private final VmRepository vms;
  private final HetznerClient hetzner;
  private final VmInventory inventory;
  private final Clock clock;
  private final SecretStore store;
  private final JsonMapper json;
  private final AtomicBoolean rewritten = new AtomicBoolean();
  // Read by every heartbeat; this instance is the only writer, so it never goes stale.
  private final Map<UUID, List<FirewallRule>> hostRules = new ConcurrentHashMap<>();

  public VmFirewallService(
      VmRepository vms,
      HetznerClient hetzner,
      VmInventory inventory,
      Clock clock,
      SecretStore store,
      JsonMapper json) {
    this.vms = vms;
    this.hetzner = hetzner;
    this.inventory = inventory;
    this.clock = clock;
    this.store = store;
    this.json = json;
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

  /**
   * A shared firewall with room for one more server, made when every one is full. One whose rules
   * never landed is passed over, or its VMs could never be reached whatever their rules say.
   */
  long shared() {
    var pool =
        hetzner.firewalls(
            HetznerVmInventory.MANAGED_BY
                + "="
                + HetznerVmInventory.MANAGER
                + ","
                + ROLE
                + "="
                + SHARED);
    for (var firewall : pool) {
      if (firewall.servers() < SHARED_SERVERS && !firewall.rules().isEmpty()) {
        return firewall.id();
      }
    }
    var id =
        hetzner.createFirewall(
            "vms-" + UUID.randomUUID(),
            Map.of(HetznerVmInventory.MANAGED_BY, HetznerVmInventory.MANAGER, ROLE, SHARED));
    hetzner.setFirewallRules(id, OPEN);
    return id;
  }

  public List<FirewallRule> rules(UUID vmId) {
    requireVm(vmId);
    return own(vmId).map(Firewall::rules).orElseGet(() -> storedRules(vmId));
  }

  /**
   * Replaces every rule. A rule with no sources lets in anyone, over IPv4 and IPv6.
   *
   * @throws FirewallRuleRefusedException when a rule is one Hetzner would refuse
   */
  public List<FirewallRule> setRules(UUID vmId, List<FirewallRule> rules) {
    var valid = validate(rules);
    requireVm(vmId);
    var own = own(vmId);
    if (own.isPresent()) {
      hetzner.setFirewallRules(own.get().id(), valid);
    } else {
      var text = json.writeValueAsString(valid);
      store.update(vmId, Entry.FIREWALL, _ -> Map.of(RULES, text));
      hostRules.put(vmId, valid);
    }
    return valid;
  }

  /**
   * What a VM that filters its own traffic is to apply; empty when they cannot be read now, so the
   * VM keeps the ones it has rather than closing every port. A VM with a firewall of its own gets
   * none it acts on, since its image applies none.
   */
  public Optional<List<FirewallRule>> hostRules(UUID vmId) {
    try {
      return Optional.of(storedRules(vmId));
    } catch (RuntimeException e) {
      log.warn("could not read the firewall rules of VM {}: {}", vmId, e.getMessage());
      return Optional.empty();
    }
  }

  private List<FirewallRule> storedRules(UUID vmId) {
    return hostRules.computeIfAbsent(
        vmId,
        id -> {
          var text = store.read(id, Entry.FIREWALL).get(RULES);
          if (text == null) {
            return List.of();
          }
          try {
            return List.of(json.readValue(text, FirewallRule[].class));
          } catch (JacksonException e) {
            throw new IllegalStateException(
                "the firewall rules of VM " + id + " are unreadable", e);
          }
        });
  }

  /** Drops what is kept of a deleted VM's rules; its Vault entry goes with its secrets. */
  void forget(UUID vmId) {
    hostRules.remove(vmId);
  }

  private void requireVm(UUID vmId) {
    if (vms.findById(vmId).isEmpty()) {
      throw new VmNotFoundException(vmId);
    }
  }

  // Only a VM from an image without its own filtering has one.
  private Optional<Firewall> own(UUID vmId) {
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
        .findFirst();
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
    if (!rewritten.get()) {
      rewrite(firewalls);
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

  // Writes each firewall in use back with its own inbound rules, once per start, so one made
  // before the client added its fixed outbound rules gets them too.
  private void rewrite(List<Firewall> firewalls) {
    var failed = false;
    for (var firewall : firewalls) {
      if (firewall.servers() == 0) {
        continue;
      }
      try {
        hetzner.setFirewallRules(firewall.id(), firewall.rules());
      } catch (HetznerException e) {
        failed = true;
        log.warn("could not rewrite firewall {}: {}", firewall.name(), e.getMessage());
      }
    }
    rewritten.set(!failed);
  }

  private boolean hasVm(Firewall firewall) {
    try {
      return vms.findById(UUID.fromString(firewall.labels().get(HetznerVmInventory.VM_ID)))
          .isPresent();
    } catch (IllegalArgumentException | NullPointerException _) {
      // Not one this app labelled; leave it alone.
      return true;
    }
  }

  static List<FirewallRule> validate(List<FirewallRule> rules) {
    if (rules.size() > MAX_RULES) {
      throw new FirewallRuleRefusedException("a VM may have at most " + MAX_RULES + " rules");
    }
    return rules.stream().map(VmFirewallService::rule).toList();
  }

  private static FirewallRule rule(FirewallRule rule) {
    var protocol = rule.protocol() == null ? "" : rule.protocol().strip().toLowerCase();
    if (!PROTOCOLS.contains(protocol)) {
      throw new FirewallRuleRefusedException("protocol must be tcp, udp or icmp");
    }
    return new FirewallRule(
        protocol, port(protocol, rule.port()), sources(rule.sourceIps()), description(rule));
  }

  private static List<String> sources(List<String> given) {
    var sources = new LinkedHashSet<String>();
    for (var source : given == null ? List.<String>of() : given) {
      sources.add(network(source));
    }
    if (sources.size() > MAX_SOURCES) {
      throw new FirewallRuleRefusedException(
          "a rule may let in at most " + MAX_SOURCES + " networks");
    }
    return sources.isEmpty() ? ANYWHERE : List.copyOf(sources);
  }

  private static String description(FirewallRule rule) {
    if (rule.description() == null || rule.description().isBlank()) {
      return null;
    }
    var description = rule.description().strip();
    if (description.length() > 255) {
      throw new FirewallRuleRefusedException("a description has at most 255 characters");
    }
    return description;
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
    } catch (IllegalArgumentException _) {
      throw new FirewallRuleRefusedException(source + " is not an IP address or network");
    }
    var bytes = parsed.getAddress();
    var bits = bytes.length * 8;
    int prefix;
    try {
      prefix = slash < 0 ? bits : Integer.parseInt(text.substring(slash + 1));
    } catch (NumberFormatException _) {
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
