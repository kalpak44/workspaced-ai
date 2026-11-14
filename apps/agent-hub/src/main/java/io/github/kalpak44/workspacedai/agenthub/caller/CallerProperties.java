package io.github.kalpak44.workspacedai.agenthub.caller;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Who may call which internal route. Each caller is a Kubernetes service account, named as the
 * TokenReview reports it ({@code system:serviceaccount:<namespace>:<name>}).
 *
 * @param audience the audience every caller's projected token must be issued for, so a token meant
 *     for the Kubernetes API or Vault is refused here
 * @param api the account that calls a VM's harness
 * @param gateway the account that calls a VM's public web server, and nothing else
 * @param vmManager the account that issues and revokes enrollment tokens
 * @param cacheFor how long a token the API server accepted is trusted without asking again
 * @param serviceAccountDir where this pod's own token and the cluster CA are mounted
 */
@ConfigurationProperties("workspaced.agent-hub.callers")
public record CallerProperties(
    @DefaultValue("workspaced-ai-agent-hub") String audience,
    @DefaultValue("system:serviceaccount:workspaced-ai:workspaced-ai-api") String api,
    @DefaultValue("system:serviceaccount:workspaced-ai:workspaced-ai-web-gateway") String gateway,
    @DefaultValue("system:serviceaccount:workspaced-ai:workspaced-ai-vm-manager") String vmManager,
    @DefaultValue("60s") Duration cacheFor,
    @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount") Path serviceAccountDir) {}
