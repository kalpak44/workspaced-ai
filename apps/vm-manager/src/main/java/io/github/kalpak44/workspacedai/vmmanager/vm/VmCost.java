package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.math.BigDecimal;

/**
 * One part of what a VM costs a month, in the Hetzner account's currency.
 *
 * @param item what is paid for: {@code server} or {@code ipv4}
 */
public record VmCost(String item, BigDecimal monthlyNet, BigDecimal monthlyGross) {}
