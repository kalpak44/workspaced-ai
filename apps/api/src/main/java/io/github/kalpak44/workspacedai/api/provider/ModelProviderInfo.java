package io.github.kalpak44.workspacedai.api.provider;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A model provider a workspace can use")
public record ModelProviderInfo(
    @Schema(description = "Sent back when creating a workspace", example = "openrouter") String id,
    @Schema(description = "Display name", example = "OpenRouter") String name,
    @Schema(
            description = "Where the workspace sends model requests",
            example = "https://openrouter.ai/api/v1")
        String baseUrl,
    @Schema(
            description = "The workspace secret the API key is stored in",
            example = "OPENROUTER_API_KEY")
        String apiKeyVariable) {

  static ModelProviderInfo of(ModelProvider provider) {
    return new ModelProviderInfo(
        provider.id(), provider.displayName(), provider.baseUrl(), provider.apiKeyVariable());
  }
}
