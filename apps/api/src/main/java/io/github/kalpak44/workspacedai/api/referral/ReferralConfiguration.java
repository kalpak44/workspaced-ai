package io.github.kalpak44.workspacedai.api.referral;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReferralProperties.class)
public class ReferralConfiguration {}
