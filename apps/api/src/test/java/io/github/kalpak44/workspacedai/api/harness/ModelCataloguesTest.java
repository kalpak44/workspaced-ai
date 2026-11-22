package io.github.kalpak44.workspacedai.api.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.provider.FakeOpenRouterPricing;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterPricing;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelCataloguesTest {
  final Harness harness = mock(Harness.class);

  ModelCataloguesTest() {
    when(harness.models(any(), any()))
        .thenReturn(
            new ModelCatalogue(
                "anthropic/claude-haiku-5.5",
                List.of(
                    new ModelOption("anthropic/claude-haiku-5.5", "Haiku", 1L, 1.0, 5.0),
                    new ModelOption("anthropic/claude-sonnet-5.5", "Sonnet", 1L, 2.0, 10.0),
                    new ModelOption("openrouter/free-model", "Free", 1L, 0.0, 0.0),
                    new ModelOption("unknown/model", "Unknown", 1L, 0.01, 0.01))));
  }

  @Test
  void defaultsToTheCheapestPaidModelAtLivePrices() {
    var catalogue =
        new ModelCatalogues(harness, new FakeOpenRouterPricing()).of(null, "openrouter");
    assertThat(catalogue.defaultModel()).isEqualTo("anthropic/claude-sonnet-5.5");
    assertThat(catalogue.models().get(1))
        .isEqualTo(new ModelOption("anthropic/claude-sonnet-5.5", "Sonnet", 1L, 0.5, 2.0));
    // OpenRouter does not price it, so the bundled price stays and it cannot be the default.
    assertThat(catalogue.models().get(3).inputCost()).isEqualTo(0.01);
  }

  @Test
  void keepsTheHarnessDefaultWhenOpenRouterCannotBeReached() {
    OpenRouterPricing unreachable = Map::of;
    var catalogue = new ModelCatalogues(harness, unreachable).of(null, "openrouter");
    assertThat(catalogue.defaultModel()).isEqualTo("anthropic/claude-haiku-5.5");
    assertThat(catalogue.models().get(1).inputCost()).isEqualTo(2.0);
  }
}
