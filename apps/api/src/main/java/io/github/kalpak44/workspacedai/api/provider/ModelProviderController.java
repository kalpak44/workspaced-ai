package io.github.kalpak44.workspacedai.api.provider;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes and documentation are declared on {@link ModelProviderApi}. */
@RestController
public class ModelProviderController implements ModelProviderApi {

  private final boolean openaiEnabled;

  public ModelProviderController(
      @Value("${workspaced.ai.openai-api-key-enabled:true}") boolean openaiEnabled) {
    this.openaiEnabled = openaiEnabled;
  }

  @Override
  public ResponseEntity<List<ModelProviderInfo>> list(boolean connectors) {
    return ResponseEntity.ok(
        Arrays.stream(ModelProvider.values())
            .filter(
                p ->
                    p == ModelProvider.OPENROUTER
                        || (p == ModelProvider.OPENAI && connectors && openaiEnabled))
            .map(ModelProviderInfo::of)
            .toList());
  }
}
