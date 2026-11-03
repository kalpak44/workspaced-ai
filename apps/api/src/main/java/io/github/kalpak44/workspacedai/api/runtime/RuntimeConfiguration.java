package io.github.kalpak44.workspacedai.api.runtime;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnCloudPlatform;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pods only when the API itself runs in Kubernetes, which Spring Boot detects from the environment
 * the kubelet sets. Anywhere else the local runtime is used, so a developer's kubeconfig is never
 * read.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkspaceProperties.class)
public class RuntimeConfiguration {

  /** In a pod, the client authenticates with the mounted service account token. */
  @Bean
  @ConditionalOnCloudPlatform(CloudPlatform.KUBERNETES)
  @ConditionalOnMissingBean
  KubernetesClient kubernetesClient() {
    return new KubernetesClientBuilder().build();
  }

  @Bean
  @ConditionalOnCloudPlatform(CloudPlatform.KUBERNETES)
  WorkspaceRuntime kubernetesWorkspaceRuntime(
      KubernetesClient client, WorkspaceProperties properties) {
    return new KubernetesWorkspaceRuntime(client, properties);
  }

  @Bean
  @ConditionalOnMissingBean(WorkspaceRuntime.class)
  WorkspaceRuntime localWorkspaceRuntime() {
    return new LocalWorkspaceRuntime();
  }
}
