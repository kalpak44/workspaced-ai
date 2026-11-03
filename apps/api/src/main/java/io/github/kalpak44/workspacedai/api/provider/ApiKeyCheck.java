package io.github.kalpak44.workspacedai.api.provider;

/** Asks a provider whether it accepts a key, before anything is stored with it. */
public interface ApiKeyCheck {

  /**
   * True when the provider accepts the key, false when it refuses it.
   *
   * @throws ProviderUnreachableException when the provider gives no answer either way
   */
  boolean accepts(ModelProvider provider, String apiKey);
}
