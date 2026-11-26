package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import tools.jackson.databind.JsonNode;

/**
 * What the VM manager answers a heartbeat with.
 *
 * @param status the VM's status, e.g. RESTARTING, which is how the VM learns its workspace was
 *     restarted
 * @param firewall the inbound rules the VM applies itself, passed on as they came; null when the VM
 *     manager sent none, which leaves the VM's own in place
 */
public record Heartbeat(String status, JsonNode firewall) {}
