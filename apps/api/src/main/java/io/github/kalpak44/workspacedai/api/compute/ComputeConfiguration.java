package io.github.kalpak44.workspacedai.api.compute;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VmManagerProperties.class)
public class ComputeConfiguration {}
