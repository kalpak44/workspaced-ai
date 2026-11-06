package io.github.kalpak44.workspacedai.api.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param jwtSecret the HS256 key login tokens are signed with, shared by every instance
 * @param tokenTtl how long a sign-in lasts before the user is asked to sign in again
 */
@ConfigurationProperties("workspaced.auth")
public record AuthProperties(String jwtSecret, @DefaultValue("12h") Duration tokenTtl) {}
