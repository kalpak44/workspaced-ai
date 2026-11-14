package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The recipe store: the routes and their OpenAPI documentation.
 *
 * @see RecipesController
 */
@Tag(
    name = "Recipes",
    description =
        "Markdown instructions users share and rate, which an agent follows to set something up"
            + " in a workspace. A private recipe is seen by its author alone, and answers 404 to"
            + " anyone else")
@RequestMapping("/recipes")
public interface RecipesApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "listRecipes",
      summary = "List recipes",
      description =
          "One page of recipes, without their Markdown: every public one, the caller's included,"
              + " or the caller's own private ones. top orders by average rating then number of"
              + " ratings, newest by when each turned public, updated by the last change; the id"
              + " breaks ties. A page holds at most 100.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = PagedModel.class)))
  @ApiResponse(
      responseCode = "400",
      description = "An unknown scope, sort or category, or a negative page.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<PagedModel<RecipeSummary>> list(
      @Parameter(description = "public or private", example = "public")
          @RequestParam(defaultValue = "public")
          String scope,
      @Parameter(description = "Matches the title, the summary or a tag, ignoring case")
          @RequestParam(required = false)
          String q,
      @Parameter(description = "Only recipes in this category", example = "websites")
          @RequestParam(required = false)
          String category,
      @Parameter(description = "top, newest or updated", example = "top")
          @RequestParam(defaultValue = "top")
          String sort,
      @Parameter(description = "From 0") @RequestParam(defaultValue = "0") int page,
      @Parameter(description = "At most 100") @RequestParam(defaultValue = "24") int size);

  @Operation(
      operationId = "getRecipe",
      summary = "Get a recipe",
      description = "The recipe with its Markdown.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Recipe.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No recipe has that id, or it is someone else's private one.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(path = "/{id}", produces = JSON)
  ResponseEntity<Recipe> get(@Parameter(description = "The recipe's id") @PathVariable UUID id);

  @Operation(
      operationId = "createRecipe",
      summary = "Create a recipe",
      description =
          "The caller is its author. Surrounding whitespace is trimmed from the title, summary and"
              + " tags, and tags are lowercased with repeats dropped.")
  @ApiResponse(
      responseCode = "201",
      description = "Created.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Recipe.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "A blank or overlong title, an overlong summary, an unknown category, more than 8 tags"
              + " or one that is not 1 to 30 letters, digits and -, or Markdown that is missing or"
              + " longer than 100,000 characters.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(consumes = JSON, produces = JSON)
  ResponseEntity<Recipe> create(@Valid @RequestBody RecipeRequest request);

  @Operation(
      operationId = "updateRecipe",
      summary = "Replace a recipe",
      description =
          "Replaces every field. Only its author may; turning it public for the first time sets"
              + " publishedAt.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Recipe.class)))
  @ApiResponse(
      responseCode = "400",
      description = "A field is invalid, as for create.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "403",
      description = "Someone else wrote it.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No recipe has that id, or it is someone else's private one.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/{id}", consumes = JSON, produces = JSON)
  ResponseEntity<Recipe> update(
      @Parameter(description = "The recipe's id") @PathVariable UUID id,
      @Valid @RequestBody RecipeRequest request);

  @Operation(
      operationId = "deleteRecipe",
      summary = "Delete a recipe",
      description = "By its author, or by an admin. Its ratings go with it.")
  @ApiResponse(responseCode = "204", description = "Deleted.")
  @ApiResponse(
      responseCode = "403",
      description = "Someone else wrote it, and the caller is not an admin.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No recipe has that id, or it is someone else's private one.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@Parameter(description = "The recipe's id") @PathVariable UUID id);

  @Operation(
      operationId = "rateRecipe",
      summary = "Rate a recipe",
      description = "Gives it 1 to 5 stars, replacing the caller's earlier rating.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = RatingResult.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The stars are missing or not 1 to 5.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No recipe has that id, or it is someone else's private one.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The caller wrote it.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/{id}/rating", consumes = JSON, produces = JSON)
  ResponseEntity<RatingResult> rate(
      @Parameter(description = "The recipe's id") @PathVariable UUID id,
      @Valid @RequestBody RatingRequest request);

  @Operation(
      operationId = "unrateRecipe",
      summary = "Remove the caller's rating",
      description = "Answers 204 whether or not the caller had rated it.")
  @ApiResponse(responseCode = "204", description = "Removed.")
  @ApiResponse(
      responseCode = "404",
      description = "No recipe has that id, or it is someone else's private one.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/{id}/rating")
  ResponseEntity<Void> unrate(@Parameter(description = "The recipe's id") @PathVariable UUID id);
}
