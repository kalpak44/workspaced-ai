package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A Cloud Firewall as Hetzner lists it, with only the fields this app reads.
 *
 * @param rules its inbound rules; a packet no rule allows is dropped
 * @param servers how many servers it applies to; zero once its server is gone
 */
public record Firewall(
    long id,
    String name,
    Instant created,
    Map<String, String> labels,
    List<FirewallRule> rules,
    int servers) {}
