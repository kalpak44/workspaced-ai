package io.github.kalpak44.workspacedai.api.compute;

import java.util.List;

/**
 * What may reach a workspace's VM from the internet.
 *
 * @param address the VM's public IPv4; null until it has one
 * @param rules its inbound rules; a packet no rule allows is dropped
 */
public record VmFirewall(String address, List<FirewallRule> rules) {}
