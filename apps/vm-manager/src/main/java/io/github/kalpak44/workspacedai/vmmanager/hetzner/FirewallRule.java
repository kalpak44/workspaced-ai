package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.util.List;

/**
 * One inbound rule of a Cloud Firewall.
 *
 * @param protocol tcp, udp or icmp
 * @param port one port, e.g. 80, or a range, e.g. 8000-8100; null for icmp, which has none
 * @param sourceIps the networks it lets in, in CIDR notation; never empty
 * @param description null for none
 */
public record FirewallRule(
    String protocol, String port, List<String> sourceIps, String description) {}
