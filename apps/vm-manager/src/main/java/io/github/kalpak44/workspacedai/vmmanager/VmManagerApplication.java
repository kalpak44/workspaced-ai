package io.github.kalpak44.workspacedai.vmmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VmManagerApplication {

  public static void main(String[] args) {
    SpringApplication.run(VmManagerApplication.class, args);
  }
}
