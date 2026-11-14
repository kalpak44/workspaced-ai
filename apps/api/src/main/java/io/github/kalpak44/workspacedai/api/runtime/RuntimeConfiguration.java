package io.github.kalpak44.workspacedai.api.runtime;

import io.github.kalpak44.workspacedai.api.compute.VmManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Every workspace runs on a Hetzner VM of its own; there is no other runtime. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebServerProperties.class)
public class RuntimeConfiguration {

  @Bean
  WorkspaceRuntime vmWorkspaceRuntime(VmManager vmManager) {
    return new VmWorkspaceRuntime(vmManager);
  }
}
