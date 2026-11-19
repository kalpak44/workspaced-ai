package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A sign-out, and where the browser goes to finish it")
public record LogoutResponse(
    @Schema(
            description = "Auth0's logout, which ends its session and returns to the app",
            example =
                "https://auth.workspaced.online/v2/logout?client_id=abc&returnTo=https%3A%2F%2Fai.workspaced.online%2F")
        String logoutUrl) {}
