package io.github.kalpak44.workspacedai.api.secret;

import io.swagger.v3.oas.annotations.media.Schema;

/** Values are never returned: once set, a secret can only be replaced or removed. */
@Schema(description = "A secret a workspace receives as an environment variable")
public record Secret(
    @Schema(description = "The environment variable name", example = "GITHUB_TOKEN") String name,
    @Schema(
            description =
                "Set by the workspace's model provider, holding the key it was created with; "
                    + "it cannot be removed")
        boolean managed) {}
