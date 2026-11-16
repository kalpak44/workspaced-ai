package io.github.kalpak44.workspacedai.api.compute;

import java.util.UUID;

/**
 * Where a workspace's VM can be reached from the internet.
 *
 * @param ipv4 its public IPv4; null until it has one
 * @param ipv6 the IPv6 it answers on; null until it has one
 * @param hostname its own name, which follows it; null when the VM manager publishes none
 */
public record VmAddress(UUID vmId, String ipv4, String ipv6, String hostname) {}
