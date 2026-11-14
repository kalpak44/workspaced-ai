package io.github.kalpak44.workspacedai.vmmanager.vault;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param url Vault's address; unset leaves the app running, and anything that needs a VM's secrets
 *     answers 503
 * @param token a fixed Vault token, for a local run; blank signs in with the pod's service account
 *     through Kubernetes auth instead
 * @param authPath where the Kubernetes auth method is mounted
 * @param role the Kubernetes auth role, which binds this app's service account to its policy
 * @param jwtPath the service account token Kubernetes mounts into the pod
 * @param kvMount the KV version 2 engine the secrets live in
 * @param prefix the path under that engine; each VM's secrets are one entry below it, named by id
 * @param timeout how long one call to Vault may take, connecting included
 */
@ConfigurationProperties("workspaced.vault")
public record VaultProperties(
    URI url,
    String token,
    @DefaultValue("kubernetes") String authPath,
    @DefaultValue("workspaced-ai-vm-manager") String role,
    @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/token") Path jwtPath,
    @DefaultValue("secret") String kvMount,
    @DefaultValue("workspaced-ai/vms") String prefix,
    @DefaultValue("10s") Duration timeout) {

  boolean isConfigured() {
    return url != null && !url.toString().isBlank();
  }

  boolean hasToken() {
    return token != null && !token.isBlank();
  }
}
