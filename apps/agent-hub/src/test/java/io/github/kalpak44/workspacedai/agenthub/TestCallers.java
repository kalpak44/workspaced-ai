package io.github.kalpak44.workspacedai.agenthub;

import io.github.kalpak44.workspacedai.agenthub.caller.CallerProperties;
import io.github.kalpak44.workspacedai.agenthub.caller.CallerVerifier;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Stands in for the API server's TokenReview: one fixed token per caller. */
@TestConfiguration(proxyBeanMethods = false)
public class TestCallers {

  public static final String API = "api-token";
  public static final String GATEWAY = "gateway-token";
  public static final String VM_MANAGER = "vm-manager-token";

  @Bean
  @Primary
  CallerVerifier testCallerVerifier(CallerProperties properties) {
    var accounts =
        Map.of(
            API, properties.api(),
            GATEWAY, properties.gateway(),
            VM_MANAGER, properties.vmManager());
    return token -> Optional.ofNullable(accounts.get(token));
  }
}
