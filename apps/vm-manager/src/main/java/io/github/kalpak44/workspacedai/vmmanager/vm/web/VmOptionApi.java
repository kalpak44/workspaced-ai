package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmOptionDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * VM options API: the routes and their OpenAPI documentation.
 *
 * @see VmOptionController
 */
@Tag(name = "VM options", description = "The sizes a VM can be created as")
@RequestMapping("/vm-options")
public interface VmOptionApi {

  String OPTION_EXAMPLE =
      "{\"serverType\":\"cx23\",\"isDefault\":true,\"cores\":2,\"memoryGb\":4.0,\"diskGb\":40,"
          + "\"cpuType\":\"shared\",\"architecture\":\"x86\",\"location\":\"nbg1\","
          + "\"monthlyNet\":5.49,\"monthlyGross\":6.59}";

  @Operation(
      operationId = "listVmOptions",
      summary = "List VM options",
      description =
          "Each serverType a create may name, in the order offered, with its specs and monthly "
              + "price as Hetzner currently sells it. Exactly one is the default.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = VmApi.JSON,
              array = @ArraySchema(schema = @Schema(implementation = VmOptionDto.class)),
              examples = @ExampleObject("[" + OPTION_EXAMPLE + "]")))
  @ApiResponse(
      responseCode = "502",
      description = "Hetzner failed or answered unexpectedly.",
      content =
          @Content(
              mediaType = VmApi.PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "No Hetzner token is configured.",
      content =
          @Content(
              mediaType = VmApi.PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = VmApi.JSON)
  ResponseEntity<List<VmOptionDto>> list();
}
