package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.time.Instant;
import java.util.Map;

/**
 * A server as Hetzner lists it, with only the fields this app reads.
 *
 * @param ipv4 its public address; null if it has none
 * @param ipv6 the address it answers on in its public /64; null if it has none
 */
public record Server(
    long id,
    String name,
    String serverType,
    String ipv4,
    String ipv6,
    Instant created,
    Map<String, String> labels) {}
