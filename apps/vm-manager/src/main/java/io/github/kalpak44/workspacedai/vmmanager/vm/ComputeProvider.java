package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.Arrays;

/**
 * The clouds a VM can be created in. Hetzner is the only one, so {@link VmService} speaks to it
 * directly; a second one means a create, stop and firewall of its own behind this choice.
 */
public enum ComputeProvider {
  HETZNER("hetzner");

  /** Where a VM is created when a create names no provider. */
  public static final ComputeProvider DEFAULT = HETZNER;

  private final String id;

  ComputeProvider(String id) {
    this.id = id;
  }

  /**
   * The provider a create asked for.
   *
   * @param id blank for the default
   * @throws ComputeProviderNotOfferedException when it is not one offered
   */
  public static ComputeProvider resolve(String id) {
    if (id == null || id.isBlank()) {
      return DEFAULT;
    }
    var wanted = id.strip();
    return Arrays.stream(values())
        .filter(p -> p.id.equals(wanted))
        .findFirst()
        .orElseThrow(
            () ->
                new ComputeProviderNotOfferedException(
                    wanted, Arrays.stream(values()).map(ComputeProvider::id).toList()));
  }

  /** What the API and the DTOs know it by. */
  public String id() {
    return id;
  }
}
