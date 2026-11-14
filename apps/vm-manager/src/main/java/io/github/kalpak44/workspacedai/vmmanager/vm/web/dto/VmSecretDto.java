package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "VmSecret", description = "A secret a VM keeps; its value is never returned")
public record VmSecretDto(
    @Schema(description = "The environment variable name", example = "GITHUB_TOKEN") String name,
    @Schema(
            description =
                "The model provider's key, set when the VM was created; it cannot be removed",
            example = "false")
        boolean system) {}
