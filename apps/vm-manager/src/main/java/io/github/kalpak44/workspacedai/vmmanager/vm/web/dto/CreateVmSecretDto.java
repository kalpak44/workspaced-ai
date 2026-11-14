package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The name becomes an environment variable on the VM, so it has to be a valid one. */
@Schema(name = "CreateVmSecret", description = "A secret to add to a VM")
public record CreateVmSecretDto(
    @Schema(example = "GITHUB_TOKEN", maxLength = 100, pattern = CreateVmSecretDto.NAME_PATTERN)
        @NotEmpty
        @Size(max = 100)
        @Pattern(regexp = CreateVmSecretDto.NAME_PATTERN)
        String name,
    @Schema(example = "ghp_…", maxLength = 65536) @NotEmpty @Size(max = 65536) String value) {

  public static final String NAME_PATTERN = "^[A-Za-z_][A-Za-z0-9_]*$";
}
