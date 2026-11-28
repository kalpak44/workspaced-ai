package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Hands a VM its harness. The only thing in this app that returns secret values, and only the agent
 * hub calls it, on behalf of a VM that has signed its request.
 */
@Service
public class HarnessService {

  /** The VM's own name, {@code <vm id>.<domain>}, which the agent gives users for what it runs. */
  public static final String HOSTNAME_VARIABLE = "WORKSPACE_HOSTNAME";

  /** The VM's public IPv4 address, for a user who would rather have it than the name. */
  public static final String ADDRESS_VARIABLE = "WORKSPACE_IP";

  private final VmSecretService secrets;
  private final VmRepository vms;
  private final VmDnsService dns;
  private final HarnessProperties properties;

  public HarnessService(
      VmSecretService secrets, VmRepository vms, VmDnsService dns, HarnessProperties properties) {
    this.secrets = secrets;
    this.vms = vms;
    this.dns = dns;
    this.properties = properties;
  }

  /**
   * @throws VmNotFoundException when there is no such VM
   * @throws HarnessNotConfiguredException when no image is configured
   */
  public Harness get(UUID vmId) {
    secrets.requireVm(vmId);
    if (!properties.isConfigured()) {
      throw new HarnessNotConfiguredException();
    }
    // Set on top, so a user's secret of the same name cannot point the agent somewhere else.
    var binding = secrets.configuration(vmId);
    var environment = new HashMap<>(binding.environment());
    dns.hostname(vmId).ifPresent(name -> environment.put(HOSTNAME_VARIABLE, name));
    vms.findById(vmId)
        .map(Vm::ipv4)
        .ifPresent(address -> environment.put(ADDRESS_VARIABLE, address));
    return new Harness(properties.image().strip(), Map.copyOf(environment), binding.revision());
  }
}
