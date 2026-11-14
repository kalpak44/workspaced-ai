package io.github.kalpak44.workspacedai.vmmanager.vm;

/** A create arrived before the VMs already at Hetzner were known. */
public class VmInventoryNotLoadedException extends RuntimeException {

  public VmInventoryNotLoadedException() {
    super("the VMs at Hetzner are not loaded yet; try again shortly");
  }
}
