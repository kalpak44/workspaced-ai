package io.github.kalpak44.workspacedai.agenthub.vmmanager;

/**
 * A VM's memory as its supervisor read it from the kernel, passed on with each heartbeat.
 *
 * @param availableBytes what the kernel could hand out without swapping, cache included
 */
public record VmMemory(long totalBytes, long availableBytes) {}
