package io.github.kalpak44.workspacedai.api.domain;

import io.github.kalpak44.workspacedai.api.compute.VmAddress;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.net.IDN;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Points a user's own domain, or a name under it, at a workspace's VM. Nothing here trusts what the
 * user or their DNS provider says happened: a name only becomes active once public DNS resolves it
 * to the VM, which is also what proves the user controls it.
 */
@Service
public class DomainService {

  static final String APEX_TEMPLATE = "workspace-apex";
  static final String HOST_TEMPLATE = "workspace";

  private static final Logger log = LoggerFactory.getLogger(DomainService.class);
  private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  private final WorkspaceDomainRepository domains;
  private final WorkspaceRepository workspaces;
  private final VmManager vmManager;
  private final DnsLookup dns;
  private final DomainConnect domainConnect;
  private final DomainConnectSigner signer;
  private final DomainProperties properties;

  public DomainService(
      WorkspaceDomainRepository domains,
      WorkspaceRepository workspaces,
      VmManager vmManager,
      DnsLookup dns,
      DomainConnect domainConnect,
      DomainConnectSigner signer,
      DomainProperties properties) {
    this.domains = domains;
    this.workspaces = workspaces;
    this.vmManager = vmManager;
    this.dns = dns;
    this.domainConnect = domainConnect;
    this.signer = signer;
    this.properties = properties;
  }

  /** Oldest first. */
  public List<WorkspaceDomain> list(UUID workspaceId) {
    requireWorkspace(workspaceId);
    return domains.findAllByWorkspaceIdOrderByCreatedAt(workspaceId).stream()
        .map(this::view)
        .toList();
  }

  /**
   * Asking again for a name the workspace already has answers what it has. Its DNS provider is
   * asked whether it applies our template, which is the one remote call that may be slow, so no
   * transaction is open while it runs.
   */
  public WorkspaceDomain attach(UUID workspaceId, String requested) {
    requireWorkspace(workspaceId);
    var name =
        normalize(requested)
            .orElseThrow(
                () ->
                    badRequest(
                        "enter a domain such as example.com, or a name under one such as"
                            + " app.example.com"));
    if (name.equals(properties.providerId()) || name.endsWith("." + properties.providerId())) {
      throw badRequest(name + " is ours; attach a domain of your own");
    }
    String zone;
    try {
      zone =
          dns.zoneOf(name)
              .orElseThrow(
                  () -> badRequest(name + " is not a domain that exists; check the spelling"));
    } catch (DnsLookupException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "DNS could not be checked; try again", e);
    }
    var host = name.equals(zone) ? "@" : name.substring(0, name.length() - zone.length() - 1);
    var existing = domains.findByWorkspaceIdAndDomainAndHost(workspaceId, zone, host);
    if (existing.isPresent()) {
      return view(existing.get());
    }
    if (domains.countByWorkspaceId(workspaceId) >= properties.maxPerWorkspace()) {
      throw badRequest(
          "a workspace has at most " + properties.maxPerWorkspace() + " domains attached");
    }
    var vm = address(workspaceId);
    var provider =
        signer.isConfigured() ? domainConnect.discover(zone, template(host)).orElse(null) : null;
    try {
      return view(
          domains.save(
              new WorkspaceDomainEntity(
                  workspaceId,
                  zone,
                  host,
                  provider,
                  vm.vmId(),
                  vm.ipv4(),
                  vm.ipv6(),
                  vm.hostname(),
                  Instant.now())));
    } catch (DataIntegrityViolationException e) {
      // Two attaches of the same name at once; the other one's is the answer.
      return domains
          .findByWorkspaceIdAndDomainAndHost(workspaceId, zone, host)
          .map(this::view)
          .orElseThrow(() -> e);
    }
  }

  /**
   * Answers the records it had, which stay at the user's DNS provider until they remove them;
   * nothing of ours can reach them.
   */
  public List<DnsRecordView> detach(UUID workspaceId, UUID domainId) {
    var domain =
        domains
            .findByIdAndWorkspaceId(domainId, workspaceId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "the workspace has no domain " + domainId));
    domains.delete(domain);
    return records(domain);
  }

  /**
   * Resolves every name: one that now reaches its VM becomes active, and an active one that no
   * longer does goes back to pending, which frees it for whichever workspace it points at now. A
   * name the resolver could not answer for keeps its status until the next pass.
   */
  @Scheduled(
      fixedDelayString = "${workspaced.domains.check-interval:PT1M}",
      initialDelayString = "${workspaced.domains.check-interval:PT1M}")
  @SchedulerLock(name = "domain-check", lockAtMostFor = "PT10M")
  public void check() {
    for (var domain : domains.findAll()) {
      try {
        check(domain);
      } catch (DnsLookupException e) {
        log.debug("could not resolve {}: {}", domain.name(), e.getMessage());
      } catch (DataIntegrityViolationException _) {
        // Another workspace still holds it active; it is freed once its DNS stops pointing there.
        log.info("{} points here but is still active for another workspace", domain.name());
      }
    }
  }

  private void check(WorkspaceDomainEntity domain) {
    var reaches = dns.ipv4(domain.name()).contains(domain.getIpv4());
    if (reaches && domain.getStatus() == DomainStatus.PENDING) {
      domains.move(domain.getId(), DomainStatus.PENDING, DomainStatus.ACTIVE, Instant.now());
    } else if (!reaches && domain.getStatus() == DomainStatus.ACTIVE) {
      domains.move(
          domain.getId(), DomainStatus.ACTIVE, DomainStatus.PENDING, domain.getVerifiedAt());
    }
  }

  private WorkspaceDomain view(WorkspaceDomainEntity domain) {
    return new WorkspaceDomain(
        domain.getId(),
        domain.name(),
        domain.getDomain(),
        domain.getHost(),
        domain.getMode().name(),
        domain.getStatus().name(),
        domain.provider() == null ? null : domain.provider().name(),
        connectUrl(domain),
        records(domain),
        domain.getCreatedAt(),
        domain.getVerifiedAt());
  }

  /**
   * Signed afresh each time rather than stored, so a rotated key needs nothing rewritten. Only
   * while pending: once active there is nothing left to approve.
   */
  private String connectUrl(WorkspaceDomainEntity domain) {
    var provider = domain.provider();
    if (provider == null || domain.getStatus() != DomainStatus.PENDING || !signer.isConfigured()) {
      return null;
    }
    var apex = "@".equals(domain.getHost());
    // The apex template sets an AAAA record, which a VM without IPv6 has nothing to put in.
    if (apex && domain.getIpv6() == null) {
      return null;
    }
    var variables = new LinkedHashMap<String, String>();
    if (apex) {
      variables.put("ipv4", domain.getIpv4());
      variables.put("ipv6", domain.getIpv6());
    }
    variables.put("vmid", domain.getVmId().toString());
    return signer.applyUrl(
        provider,
        template(domain.getHost()),
        domain.getDomain(),
        domain.getHost(),
        variables,
        properties.appUrl().replaceAll("/+$", "")
            + "/workspaces/"
            + domain.getWorkspaceId()
            + "/settings/domains",
        domain.getId().toString());
  }

  /** What the template sets, so manual mode asks for exactly what Domain Connect would apply. */
  static List<DnsRecordView> records(WorkspaceDomainEntity domain) {
    var records = new ArrayList<DnsRecordView>();
    if ("@".equals(domain.getHost())) {
      records.add(new DnsRecordView("A", "@", domain.getDomain(), domain.getIpv4()));
      if (domain.getIpv6() != null) {
        records.add(new DnsRecordView("AAAA", "@", domain.getDomain(), domain.getIpv6()));
      }
      records.add(
          new DnsRecordView("CNAME", "www", "www." + domain.getDomain(), domain.getVmHostname()));
    } else {
      records.add(
          new DnsRecordView("CNAME", domain.getHost(), domain.name(), domain.getVmHostname()));
    }
    return List.copyOf(records);
  }

  private static String template(String host) {
    return "@".equals(host) ? APEX_TEMPLATE : HOST_TEMPLATE;
  }

  private VmAddress address(UUID workspaceId) {
    VmAddress vm;
    try {
      vm = vmManager.addressFor(workspaceId);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "the workspace has no VM yet", e);
    }
    if (vm.ipv4() == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "the workspace's VM has no address yet");
    }
    if (vm.hostname() == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "the workspace's VM has no name to point a domain at");
    }
    return vm;
  }

  /**
   * Lower case, punycode for an internationalized name, and only the name: a pasted URL's scheme,
   * port, path and trailing dot are dropped. At least two labels, so a bare top-level domain is
   * refused.
   */
  static Optional<String> normalize(String requested) {
    var name =
        requested
            .strip()
            .toLowerCase(Locale.ROOT)
            .replaceFirst("^[a-z][a-z0-9+.-]*://", "")
            .replaceFirst("[/?#].*$", "")
            .replaceFirst(":\\d*$", "")
            .replaceFirst("\\.$", "");
    try {
      name = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES);
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
    var labels = name.split("\\.", -1);
    if (name.length() > 253 || labels.length < 2 || labels[labels.length - 1].matches("\\d+")) {
      return Optional.empty();
    }
    for (var label : labels) {
      if (!LABEL.matcher(label).matches()) {
        return Optional.empty();
      }
    }
    return Optional.of(name);
  }

  private void requireWorkspace(UUID workspaceId) {
    if (!workspaces.existsById(workspaceId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + workspaceId);
    }
  }

  private static ResponseStatusException badRequest(String detail) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
  }
}
