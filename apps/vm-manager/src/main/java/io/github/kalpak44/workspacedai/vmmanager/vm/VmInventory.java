package io.github.kalpak44.workspacedai.vmmanager.vm;

/**
 * Whether the VMs already at Hetzner are known. Until they are, a create could make a second server
 * for a workspace that already has one, so {@link VmService} refuses to.
 *
 * @see HetznerVmInventory
 */
@FunctionalInterface
public interface VmInventory {

  boolean isLoaded();
}
