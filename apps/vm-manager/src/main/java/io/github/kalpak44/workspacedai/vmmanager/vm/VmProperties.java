package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param serverTypes the recommended Hetzner server types, the low-cost ones; the first is the
 *     default
 * @param maxMonthlyGross the most another x86 type, with at least the default's memory, may cost a
 *     month with VAT and still be offered, so there is something to choose while the recommended
 *     ones are sold out; zero offers none
 */
@ConfigurationProperties("workspaced.vm")
public record VmProperties(
    @DefaultValue({"cx23", "cx33"}) List<String> serverTypes,
    @DefaultValue("60") BigDecimal maxMonthlyGross) {

  String defaultServerType() {
    return serverTypes.getFirst();
  }

  boolean recommends(String serverType) {
    return serverTypes.contains(serverType);
  }
}
