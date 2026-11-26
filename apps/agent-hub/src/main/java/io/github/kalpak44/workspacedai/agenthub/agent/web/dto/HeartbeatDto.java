package io.github.kalpak44.workspacedai.agenthub.agent.web.dto;

import tools.jackson.databind.JsonNode;

/**
 * What a heartbeat answers.
 *
 * @param status the VM's status at the VM manager, e.g. READY, RESTARTING or PROVISIONING
 * @param firewall the inbound rules the VM applies itself; null leaves the ones it has in place
 */
public record HeartbeatDto(String status, JsonNode firewall) {}
