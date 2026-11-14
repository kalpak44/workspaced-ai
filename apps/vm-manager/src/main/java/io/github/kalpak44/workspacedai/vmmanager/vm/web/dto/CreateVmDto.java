package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateVm", description = "A VM to create")
public record CreateVmDto(
    @Schema(
            description = "Whom it belongs to; one VM per owner",
            example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
        @NotBlank
        @Size(max = 100)
        String ownerRef,
    @Schema(
            description = "One of the serverTypes /vm-options lists; its default when omitted",
            example = "cx23")
        @Size(max = 20)
        String serverType) {}
