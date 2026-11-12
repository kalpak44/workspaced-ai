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
    return properties.endpoint() == null
        ? new UnconfiguredBackupStorage()
        : new S3BackupStorage(properties);
  }
}
