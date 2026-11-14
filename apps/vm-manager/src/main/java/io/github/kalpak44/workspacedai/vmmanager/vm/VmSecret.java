package io.github.kalpak44.workspacedai.vmmanager.vm;

/**
 * A secret a VM keeps, by name; its value is never read back.
 *
 * @param system set when the VM was created, and it cannot be removed
 */
public record VmSecret(String name, boolean system) {}
