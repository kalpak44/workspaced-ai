package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A sign-in: who it is, and the token for clients that send it themselves")
public record LoginResponse(
    Account account,
    @Schema(
            description =
                "A JWT to send as `Authorization: Bearer …`. Browsers need not: the same token is "
                    + "set as an HttpOnly cookie.")
        String token,
    @Schema(description = "When the token stops being accepted", example = "2026-11-06T20:00:00Z")
        Instant expiresAt) {}
