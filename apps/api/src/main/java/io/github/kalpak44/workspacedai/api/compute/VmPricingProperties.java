package io.github.kalpak44.workspacedai.api.compute;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param markupPercent added to what a VM costs before a user who is not an admin sees its price,
 *     e.g. 35 for 35%; admins see the cost itself
 */
@ConfigurationProperties("workspaced.vm-pricing")
public record VmPricingProperties(@DefaultValue("35") BigDecimal markupPercent) {}
