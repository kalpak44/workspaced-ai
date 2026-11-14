package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.time.Instant;
import java.util.Map;

/**
 * A server as Hetzner lists it, with only the fields this app reads.
 *
 * @param ipv4 its public address; null if it has none
 */
public record Server(
    long id,
    String name,
    String serverType,
    String ipv4,
    Instant created,
    Map<String, String> labels) {}
