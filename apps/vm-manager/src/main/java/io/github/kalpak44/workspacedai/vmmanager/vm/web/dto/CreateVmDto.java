package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(name = "CreateVm", description = "A VM to create")
public record CreateVmDto(
    @Schema(
            description = "The workspace it runs; one VM per workspace",
            example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
        @NotNull
        UUID workspaceId,
    @Schema(
            description = "Who owns the workspace; kept as a label on its Hetzner server",
            example = "00000000-0000-0000-0000-000000000001")
        @NotNull
        UUID userId,
    @Schema(
            description = "One of the serverTypes /vm-options lists; its default when omitted",
            example = "cx23")
        @Size(max = 20)
        String serverType,
    @Schema(
            description =
                "The OpenRouter key the workspace was created with, kept in Vault as the VM's "
                    + "system secret OPENROUTER_API_KEY; ignored when the workspace already has a VM",
            example = "sk-or-v1-…",
            maxLength = 1000)
        @NotBlank
        @Size(max = 1000)
        String openRouterApiKey) {}
