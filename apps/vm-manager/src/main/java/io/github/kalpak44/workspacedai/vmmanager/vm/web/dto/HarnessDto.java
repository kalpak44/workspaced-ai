package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.TreeSet;

@Schema(name = "Harness", description = "What a VM's supervisor runs")
public record HarnessDto(
    @Schema(
            description = "The harness container's image reference",
            example = "ghcr.io/kalpak44/workspaced-ai-harness:85c2bd5")
        String image,
    @Schema(
            description =
                "The container's environment: the VM's secrets, its OPENROUTER_API_KEY and its "
                    + "system environment, the system one winning on a clash",
            example = "{\"OPENROUTER_API_KEY\":\"sk-or-v1-…\",\"OPENCODE_PASSWORD\":\"…\"}")
        Map<String, String> environment) {

  // The environment holds secret values, and a record would print them.
  @Override
  public String toString() {
    return "HarnessDto[image="
        + image
        + ", environment="
        + new TreeSet<>(environment.keySet())
        + "]";
  }
}
