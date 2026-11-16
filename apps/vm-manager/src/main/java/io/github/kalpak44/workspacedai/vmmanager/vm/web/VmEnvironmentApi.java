package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.SystemVariableDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * VM environment API: the routes and their OpenAPI documentation.
 *
 * @see VmEnvironmentController
 */
@Tag(
    name = "VM environment",
    description = "The harness's system environment, which the user can neither list nor change")
@RequestMapping("/vms/{id}/environment/{name}")
public interface VmEnvironmentApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "setVmSystemVariable",
      summary = "Set one variable of a VM's system environment",
      description =
          "The harness sees it from its next start, over any secret of the same name. The key and "
              + "OPENCODE_ names are set only when the VM is created.")
  @ApiResponse(responseCode = "204", description = "Set.")
  @ApiResponse(
      responseCode = "400",
      description = "The name is not a variable name, or the value is missing or too long.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "It is the key or an OPENCODE_ name.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(consumes = JSON)
  ResponseEntity<Void> set(
      @PathVariable UUID id,
      @Parameter(description = "Variable name", example = "USER_TIMEZONE") @PathVariable
          String name,
      @Valid @RequestBody SystemVariableDto body);

  @Operation(
      operationId = "removeVmSystemVariable",
      summary = "Remove one variable from a VM's system environment",
      description = "One it does not have is not an error.")
  @ApiResponse(responseCode = "204", description = "Removed.")
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "It is the key or an OPENCODE_ name.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping
  ResponseEntity<Void> remove(@PathVariable UUID id, @PathVariable String name);
}
