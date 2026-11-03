package io.github.kalpak44.workspacedai.api.runtime;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import java.util.HashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** An in-memory API server for the application context, replacing the in-cluster client. */
@TestConfiguration(proxyBeanMethods = false)
public class MockKubernetes {

  @Bean(destroyMethod = "destroy")
  KubernetesMockServer kubernetesMockServer() {
    var server =
        new KubernetesMockServer(
            new Context(),
            new MockWebServer(),
            new HashMap<>(),
            new KubernetesCrudDispatcher(),
            false);
    server.init();
    return server;
  }

  @Bean
  KubernetesClient kubernetesClient(KubernetesMockServer server) {
    return server.createClient();
  }
}
