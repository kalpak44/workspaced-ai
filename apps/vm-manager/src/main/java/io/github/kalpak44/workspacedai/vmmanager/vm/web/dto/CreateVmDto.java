package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
            description =
                "Who owns the workspace. Each user's VMs share a spread placement group, so no "
                    + "two of them sit on one physical host",
            example = "00000000-0000-0000-0000-000000000001")
        @NotNull
        UUID userId,
    @Schema(
            description = "One of the serverTypes /vm-options lists; its default when omitted",
            example = "cx23")
        @Size(max = 20)
        String serverType) {}
