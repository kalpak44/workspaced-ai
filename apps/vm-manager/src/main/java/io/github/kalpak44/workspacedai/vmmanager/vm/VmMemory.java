package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.time.Instant;

/**
 * A VM's memory as its supervisor last reported it with a heartbeat.
 *
 * @param availableBytes what the kernel could hand out without swapping, cache included
 */
public record VmMemory(long totalBytes, long availableBytes, Instant reportedAt) {}
