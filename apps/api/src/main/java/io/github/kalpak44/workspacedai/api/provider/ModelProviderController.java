package io.github.kalpak44.workspacedai.api.provider;

import java.util.Arrays;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes and documentation are declared on {@link ModelProviderApi}. */
@RestController
public class ModelProviderController implements ModelProviderApi {

  @Override
  public ResponseEntity<List<ModelProviderInfo>> list() {
    return ResponseEntity.ok(
        Arrays.stream(ModelProvider.values()).map(ModelProviderInfo::of).toList());
  }
}
