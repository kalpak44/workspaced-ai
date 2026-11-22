package io.github.kalpak44.workspacedai.api.provider;

import java.util.Map;

/**
 * Live prices that rank the fake harness's models the other way round from OpenCode's bundled copy,
 * so a test can tell which one was used.
 */
public class FakeOpenRouterPricing implements OpenRouterPricing {
  @Override
  public Map<String, Price> prices() {
    return Map.of(
        "anthropic/claude-haiku-5.5", new Price(3, 15),
        "anthropic/claude-sonnet-5.5", new Price(0.5, 2),
        "openrouter/free-model", new Price(0, 0));
  }
}
