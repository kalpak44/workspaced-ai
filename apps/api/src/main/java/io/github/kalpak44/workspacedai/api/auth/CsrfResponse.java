package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The CSRF token a cookie-authenticated change has to carry")
public record CsrfResponse(
    @Schema(example = "X-XSRF-TOKEN") String headerName,
    @Schema(description = "Masked afresh on every call; any of them is accepted") String token) {}
