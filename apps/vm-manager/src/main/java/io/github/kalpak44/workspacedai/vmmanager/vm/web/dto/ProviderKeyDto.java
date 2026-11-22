package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only the protected provider key can be changed with this request. */
public record ProviderKeyDto(@NotBlank @Size(max = 1000) String value) {}
