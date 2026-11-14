package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.time.Instant;
import java.util.UUID;

/**
 * A VM one owner runs on. The domain model: what the API answers is {@code VmDto}, so this can
 * change without changing the contract.
 *
 * @param ownerRef whom it belongs to; opaque to the manager, a workspace id in practice
 * @param ipv4 its public address; null until it has one
 * @param lastSeenAt when it last reported ready or a heartbeat; null before it has
 */
public record Vm(
    UUID id,
    String ownerRef,
    String serverType,
    VmStatus status,
    String ipv4,
    Instant createdAt,
    Instant lastSeenAt) {

  Vm withStatus(VmStatus status) {
    return new Vm(id, ownerRef, serverType, status, ipv4, createdAt, lastSeenAt);
  }

  Vm seen(VmStatus status, Instant at) {
    return new Vm(id, ownerRef, serverType, status, ipv4, createdAt, at);
  }
}
