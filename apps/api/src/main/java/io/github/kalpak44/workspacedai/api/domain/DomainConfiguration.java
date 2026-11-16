package io.github.kalpak44.workspacedai.api.domain;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DomainProperties.class)
public class DomainConfiguration {}
