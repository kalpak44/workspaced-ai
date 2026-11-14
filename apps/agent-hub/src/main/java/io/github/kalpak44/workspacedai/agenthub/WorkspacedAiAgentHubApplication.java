package io.github.kalpak44.workspacedai.agenthub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkspacedAiAgentHubApplication {

  public static void main(String[] args) {
    SpringApplication.run(WorkspacedAiAgentHubApplication.class, args);
  }
}
