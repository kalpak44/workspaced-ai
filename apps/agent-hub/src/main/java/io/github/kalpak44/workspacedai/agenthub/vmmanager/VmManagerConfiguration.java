package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The HTTP client every VM manager call goes through, one per app so connections are reused. */
@Configuration(proxyBeanMethods = false)
public class VmManagerConfiguration {

  @Bean
  HttpClient vmManagerHttpClient(AgentHubProperties properties) {
    return HttpClient.newBuilder().connectTimeout(properties.vmManagerTimeout()).build();
  }
}
