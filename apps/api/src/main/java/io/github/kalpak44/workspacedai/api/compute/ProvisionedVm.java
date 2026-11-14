package io.github.kalpak44.workspacedai.api.compute;

import java.util.UUID;

/**
 * A VM the VM manager keeps for a workspace.
 *
 * @param status PROVISIONING until the VM reports ready, then READY, or RESTARTING
 */
public record ProvisionedVm(UUID id, UUID workspaceId, String serverType, String status) {}
