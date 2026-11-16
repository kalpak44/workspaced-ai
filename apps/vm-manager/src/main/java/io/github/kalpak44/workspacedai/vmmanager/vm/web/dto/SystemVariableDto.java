package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "SystemVariable", description = "A value of the harness's system environment")
public record SystemVariableDto(
    @Schema(example = "Europe/Sofia", maxLength = 4096) @NotNull @Size(max = 4096) String value) {}
