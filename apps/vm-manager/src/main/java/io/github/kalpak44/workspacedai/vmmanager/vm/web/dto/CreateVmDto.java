package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.TreeSet;
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
        String openRouterApiKey,
    @Schema(
            description =
                "The harness's system environment, kept in Vault apart from the user's secrets and "
                    + "handed to the harness on the VM; OPENROUTER_API_KEY here is ignored in "
                    + "favour of openRouterApiKey. Ignored when the workspace already has a VM",
            example =
                "{\"OPENROUTER_BASE_URL\":\"https://openrouter.ai/api/v1\","
                    + "\"OPENCODE_PASSWORD\":\"…\"}")
        @Size(max = 100)
        Map<
                @NotEmpty @Size(max = 100) @Pattern(regexp = CreateVmSecretDto.NAME_PATTERN) String,
                @NotNull @Size(max = 65536) String>
            environment) {

  public CreateVmDto {
    environment = environment == null ? Map.of() : environment;
  }

  // The key and the environment are secret, and a record would print them.
  @Override
  public String toString() {
    return "CreateVmDto[workspaceId="
        + workspaceId
        + ", userId="
        + userId
        + ", serverType="
        + serverType
        + ", environment="
        + new TreeSet<>(environment.keySet())
        + "]";
  }
}
