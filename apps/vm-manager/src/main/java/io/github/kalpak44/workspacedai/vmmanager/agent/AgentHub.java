package io.github.kalpak44.workspacedai.vmmanager.agent;

import java.util.Optional;
import java.util.UUID;

/**
 * The agent hub, which every VM's supervisor enrolls with and then reports to. An interface so the
 * VM service is tested without it.
 *
 * @see HttpAgentHub
 */
public interface AgentHub {

  /**
   * The cloud-config a new VM boots with: its id, where to reach the agent hub, and a one-time
   * enrollment token, replacing any issued before. Empty when no agent hub is configured.
   *
   * @throws AgentHubException when the agent hub refuses or fails
   */
  Optional<String> bootstrap(UUID vmId);

  /** Forgets the VM's agent and any token it was issued; one with neither is not an error. */
  void revoke(UUID vmId);
}
