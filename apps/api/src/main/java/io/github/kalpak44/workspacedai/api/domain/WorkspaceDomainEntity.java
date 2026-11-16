package io.github.kalpak44.workspacedai.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A name pointed at a workspace's VM, with where the VM was reached when it was attached. */
@Entity
@Table(name = "workspace_domain")
class WorkspaceDomainEntity {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(nullable = false)
  private String domain;

  @Column(nullable = false)
  private String host;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DomainMode mode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DomainStatus status;

  @Column(name = "provider_name")
  private String providerName;

  @Column(name = "connect_url")
  private String connectUrl;

  @Column(name = "vm_id", nullable = false)
  private UUID vmId;

  @Column(nullable = false)
  private String ipv4;

  private String ipv6;

  @Column(name = "vm_hostname", nullable = false)
  private String vmHostname;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  protected WorkspaceDomainEntity() {}

  WorkspaceDomainEntity(
      UUID workspaceId,
      String domain,
      String host,
      DnsProvider provider,
      UUID vmId,
      String ipv4,
      String ipv6,
      String vmHostname,
      Instant createdAt) {
    this.id = UUID.randomUUID();
    this.workspaceId = workspaceId;
    this.domain = domain;
    this.host = host;
    this.mode = provider == null ? DomainMode.MANUAL : DomainMode.DOMAIN_CONNECT;
    this.status = DomainStatus.PENDING;
    this.providerName = provider == null ? null : provider.name();
    this.connectUrl = provider == null ? null : provider.syncUrl();
    this.vmId = vmId;
    this.ipv4 = ipv4;
    this.ipv6 = ipv6;
    this.vmHostname = vmHostname;
    this.createdAt = createdAt;
  }

  UUID getId() {
    return id;
  }

  UUID getWorkspaceId() {
    return workspaceId;
  }

  String getDomain() {
    return domain;
  }

  String getHost() {
    return host;
  }

  /** The full name, e.g. app.example.com. */
  String name() {
    return "@".equals(host) ? domain : host + "." + domain;
  }

  DomainMode getMode() {
    return mode;
  }

  DomainStatus getStatus() {
    return status;
  }

  /** The user's DNS provider, when it applies our template; null otherwise. */
  DnsProvider provider() {
    return connectUrl == null ? null : new DnsProvider(providerName, connectUrl);
  }

  UUID getVmId() {
    return vmId;
  }

  String getIpv4() {
    return ipv4;
  }

  String getIpv6() {
    return ipv6;
  }

  String getVmHostname() {
    return vmHostname;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getVerifiedAt() {
    return verifiedAt;
  }
}
