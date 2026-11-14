package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param serverTypes the Hetzner server types a VM may be created as, in the order they are
 *     offered; the first is the default
 */
@ConfigurationProperties("workspaced.vm")
public record VmProperties(@DefaultValue({"cx23", "cx33"}) List<String> serverTypes) {

  String defaultServerType() {
    return serverTypes.getFirst();
  }

  boolean offers(String serverType) {
    return serverTypes.contains(serverType);
  }
}
