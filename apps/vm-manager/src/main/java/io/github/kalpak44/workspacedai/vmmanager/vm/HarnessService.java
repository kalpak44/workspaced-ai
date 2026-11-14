package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Hands a VM its harness. The only thing in this app that returns secret values, and only the agent
 * hub calls it, on behalf of a VM that has signed its request.
 */
@Service
public class HarnessService {

  private final VmSecretService secrets;
  private final HarnessProperties properties;

  public HarnessService(VmSecretService secrets, HarnessProperties properties) {
    this.secrets = secrets;
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
    return new Harness(properties.image().strip(), secrets.environment(vmId));
  }
}
