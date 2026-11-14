package io.github.kalpak44.workspacedai.agenthub.agent.web.dto;

/**
 * What a heartbeat answers.
 *
 * @param status the VM's status at the VM manager, e.g. READY, RESTARTING or PROVISIONING
 */
public record HeartbeatDto(String status) {}
