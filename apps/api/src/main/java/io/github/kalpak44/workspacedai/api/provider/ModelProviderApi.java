package io.github.kalpak44.workspacedai.api.provider;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Model provider API: the routes and their OpenAPI documentation.
 *
 * @see ModelProviderController
 */
@Tag(name = "Model providers", description = "Where a workspace's models run")
@RequestMapping("/model-providers")
public interface ModelProviderApi {

  /**
   * Lists every supported model provider.
   *
   * @return the providers
   */
  @Operation(
      operationId = "listModelProviders",
      summary = "List model providers",
      description = "The providers a workspace can be created with.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = "application/json",
              array = @ArraySchema(schema = @Schema(implementation = ModelProviderInfo.class)),
              examples =
                  @ExampleObject(
                      "[{\"id\":\"openrouter\",\"name\":\"OpenRouter\","
                          + "\"baseUrl\":\"https://openrouter.ai/api/v1\","
                          + "\"apiKeyVariable\":\"OPENROUTER_API_KEY\"}]")))
  @GetMapping(produces = "application/json")
  ResponseEntity<List<ModelProviderInfo>> list();
}
