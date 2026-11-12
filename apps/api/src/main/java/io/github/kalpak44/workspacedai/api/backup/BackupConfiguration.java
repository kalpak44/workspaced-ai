package io.github.kalpak44.workspacedai.api.backup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BackupProperties.class)
public class BackupConfiguration {

  @Bean
  @ConditionalOnMissingBean
  BackupStorage backupStorage(BackupProperties properties) {
    // An unset variable reaches here as an empty URI rather than null.
    var endpoint = properties.endpoint();
    return endpoint == null || endpoint.toString().isBlank()
        ? new UnconfiguredBackupStorage()
        : new S3BackupStorage(properties);
  }
}
