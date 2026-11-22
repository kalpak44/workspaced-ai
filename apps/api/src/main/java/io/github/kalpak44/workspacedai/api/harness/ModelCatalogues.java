package io.github.kalpak44.workspacedai.api.harness;

import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterPricing;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The harness's catalogue, priced by the provider itself rather than by the copy OpenCode ships
 * with. A workspace that names no model gets the one the harness's configuration names.
 */
@Component
public class ModelCatalogues {
  private final Harness harness;
  private final OpenRouterPricing pricing;

  public ModelCatalogues(Harness harness, OpenRouterPricing pricing) {
    this.harness = harness;
    this.pricing = pricing;
  }

  public ModelCatalogue of(HarnessEndpoint endpoint, String providerId) {
    var catalogue = harness.models(endpoint, providerId);
    if (!ModelProvider.OPENROUTER.id().equals(providerId)) {
      return catalogue;
    }
    var prices = pricing.prices();
    if (prices.isEmpty()) {
      return catalogue;
    }
    List<ModelOption> models =
        catalogue.models().stream()
            .map(
                m -> {
                  var price = prices.get(m.id());
                  return price == null
                      ? m
                      : new ModelOption(
                          m.id(),
                          m.name(),
                          m.contextLength(),
                          price.inputCost(),
                          price.outputCost());
                })
            .toList();
    return new ModelCatalogue(catalogue.defaultModel(), models);
  }
}
