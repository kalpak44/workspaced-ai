package io.github.kalpak44.workspacedai.api.funnel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The sign-up funnel: the route and its OpenAPI documentation. For admins only; it answers 403 to
 * anyone else.
 *
 * @see FunnelController
 */
@Tag(name = "Funnel", description = "Where sign-ups come from and how far they get")
@RequestMapping("/admin/funnel")
public interface FunnelApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "getFunnel",
      summary = "Get the sign-up funnel",
      description =
          "Of the accounts made between two dates, how many started the demo, paid, and were"
              + " still charged 30 days after signing up, in total and per sign-up source, and how"
              + " far they got in the guided tour. Admins are left out.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Funnel.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The dates are reversed, or more than a year apart.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "403",
      description = "The signed-in user is not an admin.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<Funnel> funnel(
      @Parameter(description = "First day of sign-ups, UTC; 29 days before `to` when omitted")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @Parameter(description = "Last day of sign-ups, UTC; today when omitted")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to);
}
