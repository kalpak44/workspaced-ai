package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.time.Instant;
import java.util.UUID;

/**
 * A VM one workspace runs on. The domain model: what the API answers is {@code VmDto}, so this can
 * change without changing the contract.
 *
 * @param workspaceId the workspace it runs; also a label on its Hetzner server
 * @param userId who owns that workspace; also a label on its Hetzner server
 * @param serverId the Hetzner server it runs as
 * @param ipv4 its public address; null until it has one
 * @param lastSeenAt when it last reported ready or a heartbeat; null before it has
 */
public record Vm(
    UUID id,
    UUID workspaceId,
    UUID userId,
    String serverType,
    long serverId,
    VmStatus status,
    String ipv4,
    Instant createdAt,
    Instant lastSeenAt) {

  Vm withStatus(VmStatus status) {
    return new Vm(
        id, workspaceId, userId, serverType, serverId, status, ipv4, createdAt, lastSeenAt);
  }

  Vm seen(VmStatus status, Instant at) {
    return new Vm(id, workspaceId, userId, serverType, serverId, status, ipv4, createdAt, at);
  }
}
