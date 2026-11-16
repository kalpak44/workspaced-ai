package io.github.kalpak44.workspacedai.vmmanager.cloudflare;

/**
 * A record in the zone, with only the fields this app reads.
 *
 * @param name the full name, e.g. {@code <vm id>.vm.workspaced.online}
 * @param content the address an A or AAAA record points at
 */
public record DnsRecord(String id, String type, String name, String content) {}
