package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "A VM to create")
public record CreateVmRequest(
    @Schema(
            description = "Whom it belongs to; one VM per owner",
            example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
        @NotBlank
        @Size(max = 100)
        String ownerRef,
    @Schema(description = "Hetzner server type; cx22 when omitted", example = "cx22")
        @Size(max = 20)
        String serverType) {

  static final String DEFAULT_SERVER_TYPE = "cx22";

  String serverTypeOrDefault() {
    return serverType == null || serverType.isBlank() ? DEFAULT_SERVER_TYPE : serverType.trim();
  }
}
