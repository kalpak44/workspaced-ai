package io.github.kalpak44.workspacedai.api.billing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfiguration {}
