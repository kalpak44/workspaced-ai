package io.github.kalpak44.workspacedai.agenthub.caller;

import java.net.URI;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reviews callers' tokens through the API server the pod runs under. Outside a cluster, or without
 * its own token, every internal call is refused rather than let through.
 */
@Configuration(proxyBeanMethods = false)
public class CallerConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CallerConfiguration.class);

  @Bean
  CallerVerifier callerVerifier(
      CallerProperties properties, Environment environment, JsonMapper json, Clock clock) {
    var host = environment.getProperty("KUBERNETES_SERVICE_HOST");
    var port = environment.getProperty("KUBERNETES_SERVICE_PORT", "443");
    if (host == null) {
      log.warn("Not in a cluster: every internal route will be refused");
      return _ -> java.util.Optional.empty();
    }
    var bracketed = host.contains(":") ? "[" + host + "]" : host;
    try {
      return new TokenReviewVerifier(
          properties, URI.create("https://" + bracketed + ":" + port), json, clock);
    } catch (Exception e) {
      log.warn("Cannot review tokens ({}): every internal route will be refused", e.getMessage());
      return _ -> java.util.Optional.empty();
    }
  }
}
