package io.github.kalpak44.workspacedai.api.provider;

import java.util.Arrays;
import java.util.Optional;

/**
 * The model providers a workspace can use. Hardcoded: adding one means a key check that works
 * against its API, which is code, not configuration.
 */
public enum ModelProvider {
  OPENROUTER(
      "openrouter",
      "OpenRouter",
      "https://openrouter.ai/api/v1",
      "OPENROUTER_API_KEY",
      "OPENROUTER_BASE_URL",
      // Answers 401 for a key OpenRouter does not know, and costs nothing for one it does.
      "/key");

  private final String id;
  private final String displayName;
  private final String baseUrl;
  private final String apiKeyVariable;
  private final String baseUrlVariable;
  private final String keyCheckPath;

  ModelProvider(
      String id,
      String displayName,
      String baseUrl,
      String apiKeyVariable,
      String baseUrlVariable,
      String keyCheckPath) {
    this.id = id;
    this.displayName = displayName;
    this.baseUrl = baseUrl;
    this.apiKeyVariable = apiKeyVariable;
    this.baseUrlVariable = baseUrlVariable;
    this.keyCheckPath = keyCheckPath;
  }

  public static Optional<ModelProvider> byId(String id) {
    return Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst();
  }

  public String id() {
    return id;
  }

  public String displayName() {
    return displayName;
  }

  public String baseUrl() {
    return baseUrl;
  }

  /** The secret the workspace receives the user's key in. */
  public String apiKeyVariable() {
    return apiKeyVariable;
  }

  /** The config entry the workspace receives the base URL in. */
  public String baseUrlVariable() {
    return baseUrlVariable;
  }

  String keyCheckUrl() {
    return baseUrl + keyCheckPath;
  }

  /** Workspace variables the provider sets, which a user secret must not shadow. */
  public boolean reserves(String variable) {
    return apiKeyVariable.equals(variable) || baseUrlVariable.equals(variable);
  }
}
