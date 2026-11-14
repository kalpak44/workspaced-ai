package io.github.kalpak44.workspacedai.vmmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkspacedAiVmManagerApplication {

  public static void main(String[] args) {
    SpringApplication.run(WorkspacedAiVmManagerApplication.class, args);
  }
}
