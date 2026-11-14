package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.CreateVmSecretDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmSecretDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * VM secret API: the routes and their OpenAPI documentation.
 *
 * @see VmSecretController
 */
@Tag(name = "VM secrets", description = "The secrets each VM keeps in Vault")
@RequestMapping("/vms/{id}/secrets")
public interface VmSecretApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "listVmSecrets",
      summary = "List a VM's secrets",
      description =
          "Names only; values are never returned. The system secret OPENROUTER_API_KEY comes "
              + "first, then the rest by name.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = VmSecretDto.class)),
              examples =
                  @ExampleObject(
                      "[{\"name\":\"OPENROUTER_API_KEY\",\"system\":true},"
                          + "{\"name\":\"GITHUB_TOKEN\",\"system\":false}]")))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "502",
      description = "Vault failed.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "No Vault address is configured.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<List<VmSecretDto>> list(@PathVariable UUID id);

  @Operation(
      operationId = "addVmSecret",
      summary = "Add a secret to a VM",
      description =
          "Kept in Vault and handed to the VM's harness as an environment variable. A VM holds "
              + "at most 50 secrets besides its system secret.")
  @ApiResponse(
      responseCode = "201",
      description = "Added; its URL is in the Location header.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = VmSecretDto.class),
              examples = @ExampleObject("{\"name\":\"GITHUB_TOKEN\",\"system\":false}")))
  @ApiResponse(
      responseCode = "400",
      description =
          "The name is not a valid environment variable name or is too long, or the value is "
              + "empty or too long.",
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
      description =
          "The VM already has that secret, it is the system secret, it would shadow the "
              + "workspace's system environment (its name is set there or starts with "
              + "OPENCODE_), or the VM already holds 50.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(consumes = JSON, produces = JSON)
  ResponseEntity<VmSecretDto> add(
      @PathVariable UUID id, @Valid @RequestBody CreateVmSecretDto body);

  @Operation(
      operationId = "removeVmSecret",
      summary = "Remove a secret from a VM",
      description =
          "Its value goes from Vault, earlier versions included. The system secret stays.")
  @ApiResponse(responseCode = "204", description = "Removed.")
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM, or it has no secret of that name.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "It is the system secret.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/{name}")
  ResponseEntity<Void> remove(
      @PathVariable UUID id,
      @Parameter(description = "Secret name", example = "GITHUB_TOKEN") @PathVariable String name);
}
