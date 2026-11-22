package io.github.kalpak44.workspacedai.api.provider;

import java.util.Map;

/** What OpenRouter charges for each of its models right now. */
public interface OpenRouterPricing {

  /**
   * @param inputCost USD per million input tokens
   * @param outputCost USD per million output tokens
   */
  record Price(double inputCost, double outputCost) {}

  /** Prices by OpenRouter model id; empty when OpenRouter cannot be reached. */
  Map<String, Price> prices();
}
