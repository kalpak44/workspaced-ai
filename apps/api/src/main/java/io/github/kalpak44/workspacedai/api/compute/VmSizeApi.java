package io.github.kalpak44.workspacedai.api.compute;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The sizes a workspace's VM can be created as: the routes and their OpenAPI documentation.
 *
 * @see VmSizeController
 */
@Tag(name = "VM sizes", description = "What a workspace's VM can be created as")
@RequestMapping("/vm-sizes")
public interface VmSizeApi {

  @Operation(
      operationId = "listVmSizes",
      summary = "List VM sizes",
      description =
          "Every size a new workspace's VM can be created as, default first, with its specs as "
              + "Hetzner currently sells it.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = "application/json",
              array = @ArraySchema(schema = @Schema(implementation = VmSize.class))))
  @ApiResponse(
      responseCode = "502",
      description = "The VM manager, or Hetzner behind it, failed.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The VM manager cannot be reached, so no workspace can be created now.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = "application/json")
  ResponseEntity<List<VmSize>> list();
}
