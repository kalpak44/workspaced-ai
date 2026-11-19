package io.github.kalpak44.workspacedai.api.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param jwtSecret the HS256 key the session tokens issued after an Auth0 sign-in are signed with,
 *     and download links too; shared by every instance
 * @param tokenTtl how long a session lasts before the browser goes back through Auth0
 * @param issuer the Auth0 tenant users sign in with, ending in a slash as its tokens state it
 * @param clientId the Auth0 application the API signs users in as
 * @param clientSecret that application's secret
 * @param connection the Auth0 database the accounts live in, which password resets name
 * @param managementApi Auth0's Management API, on the tenant's own domain rather than the custom
 *     one, ending in a slash
 */
@ConfigurationProperties("workspaced.auth")
public record AuthProperties(
    String jwtSecret,
    @DefaultValue("12h") Duration tokenTtl,
    String issuer,
    String clientId,
    String clientSecret,
    @DefaultValue("Username-Password-Authentication") String connection,
    String managementApi) {}
