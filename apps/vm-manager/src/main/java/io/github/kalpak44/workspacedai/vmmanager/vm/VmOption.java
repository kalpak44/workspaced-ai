package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.math.BigDecimal;

/**
 * A size a VM can be created as, as Hetzner currently sells it in the configured location.
 *
 * @param isDefault taken when a create names no server type
 * @param monthlyNet monthly price before VAT, in the Hetzner account's currency
 * @param recommended one of the configured low-cost sizes, rather than a pricier stand-in
 * @param available false while Hetzner has it sold out there, when a create would be refused
 */
public record VmOption(
    String serverType,
    boolean isDefault,
    int cores,
    double memoryGb,
    int diskGb,
    String cpuType,
    String architecture,
    String location,
    BigDecimal monthlyNet,
    BigDecimal monthlyGross,
    boolean recommended,
    boolean available) {}
