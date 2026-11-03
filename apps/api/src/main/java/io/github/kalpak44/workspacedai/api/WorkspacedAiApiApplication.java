package io.github.kalpak44.workspacedai.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkspacedAiApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(WorkspacedAiApiApplication.class, args);
  }
}
