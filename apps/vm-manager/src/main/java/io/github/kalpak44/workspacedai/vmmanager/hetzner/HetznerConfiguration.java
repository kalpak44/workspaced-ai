package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The HTTP client every Hetzner call goes through, one per app so connections are reused. */
@Configuration(proxyBeanMethods = false)
public class HetznerConfiguration {

  @Bean
  HttpClient hetznerHttpClient(HetznerProperties properties) {
    return HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
  }
}
