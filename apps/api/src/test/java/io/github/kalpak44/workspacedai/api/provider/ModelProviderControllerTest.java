package io.github.kalpak44.workspacedai.api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelProviderControllerTest {
  @Test
  void disabledOpenAiIsNotOfferedForConnectors() {
    assertThat(new ModelProviderController(false).list(true).getBody())
        .extracting(ModelProviderInfo::id)
        .containsExactly("openrouter");
  }

  @Test
  void openAiIsOnlyOfferedForSavedConnectors() {
    var controller = new ModelProviderController(true);
    assertThat(controller.list(false).getBody())
        .extracting(ModelProviderInfo::id)
        .containsExactly("openrouter");
    assertThat(controller.list(true).getBody())
        .extracting(ModelProviderInfo::id)
        .containsExactly("openrouter", "openai");
  }
}
