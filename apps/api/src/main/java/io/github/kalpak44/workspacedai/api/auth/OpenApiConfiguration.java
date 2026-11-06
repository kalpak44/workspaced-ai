package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Documents both ways a token can arrive. Swagger UI on the same host is already signed in through
 * the cookie; the bearer scheme is for pasting a token from /auth/login.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
    security = {@SecurityRequirement(name = "bearer"), @SecurityRequirement(name = "cookie")})
@SecurityScheme(
    name = "bearer",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
@SecurityScheme(
    name = "cookie",
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.COOKIE,
    paramName = SecurityConfiguration.COOKIE)
class OpenApiConfiguration {}
