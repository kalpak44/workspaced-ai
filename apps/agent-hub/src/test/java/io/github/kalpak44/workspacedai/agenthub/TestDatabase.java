package io.github.kalpak44.workspacedai.agenthub;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The major the homelab server runs, so the tests see the same server behaviour. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:18-alpine");
  }
}
