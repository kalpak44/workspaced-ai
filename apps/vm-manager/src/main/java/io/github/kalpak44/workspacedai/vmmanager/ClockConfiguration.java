package io.github.kalpak44.workspacedai.vmmanager;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One clock for the whole app, so a test can replace it instead of waiting on the real one. */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
