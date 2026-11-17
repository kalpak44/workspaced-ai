package io.github.kalpak44.workspacedai.api.billing;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The signed-in user's balance and top-ups: the routes and their OpenAPI documentation.
 *
 * @see BillingController
 */
@Tag(
    name = "Billing",
    description =
        "The account's balance, topped up through Stripe Checkout, with Stripe's invoice for each"
            + " payment")
@RequestMapping("/billing")
public interface BillingApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "getBilling",
      summary = "Get the balance, spending and top-ups",
      description =
          "The balance, what the workspaces cost and until when the balance pays for them, what"
              + " they were charged each of the last 30 days, and every top-up but the abandoned"
              + " ones, newest first.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Billing.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<Billing> get();

  @Operation(
      operationId = "startTopUp",
      summary = "Start a top-up",
      description =
          "Opens a Stripe Checkout page for the amount. The balance moves only once Stripe reports"
              + " the payment, and the browser comes back to the billing page with ?top_up=<id>.")
  @ApiResponse(
      responseCode = "201",
      description = "Created.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Checkout.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The amount is outside €5.00 to €1,000.00.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "502",
      description = "Stripe refused the checkout.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Payments are not configured, or Stripe cannot be reached.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/top-ups", consumes = JSON, produces = JSON)
  ResponseEntity<Checkout> startTopUp(@Valid @RequestBody TopUpRequest request);

  @Operation(
      operationId = "refreshTopUp",
      summary = "Refresh a top-up",
      description =
          "Reads the payment back from Stripe and credits it if it is paid, so the balance is"
              + " current when the browser returns before Stripe's webhook arrives.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = TopUp.class)))
  @ApiResponse(
      responseCode = "404",
      description = "The user has no top-up with that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/top-ups/{id}/refresh", produces = JSON)
  ResponseEntity<TopUp> refresh(@Parameter(description = "Top-up id") @PathVariable UUID id);

  @Operation(
      operationId = "downloadInvoice",
      summary = "Download a top-up's invoice",
      description = "Redirects to the PDF of the invoice Stripe issued for the paid top-up.")
  @ApiResponse(responseCode = "303", description = "See the PDF at the Location header.")
  @ApiResponse(
      responseCode = "404",
      description = "No such top-up, it is not paid, or Stripe has not issued its invoice yet.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping("/top-ups/{id}/invoice")
  ResponseEntity<Void> invoice(@Parameter(description = "Top-up id") @PathVariable UUID id);

  @Operation(
      operationId = "startCardSetup",
      summary = "Save a card for automatic top-ups",
      description =
          "Opens a Stripe Checkout page in setup mode, which saves a card without charging it."
              + " The browser comes back to the billing page with ?card_session=<id>, to pass to"
              + " POST /billing/card/confirm. A new card replaces the saved one.")
  @ApiResponse(
      responseCode = "201",
      description = "Created.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = CardCheckout.class)))
  @ApiResponse(
      responseCode = "502",
      description = "Stripe refused the checkout.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Payments are not configured, or Stripe cannot be reached.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/card", produces = JSON)
  ResponseEntity<CardCheckout> startCardSetup();

  @Operation(
      operationId = "confirmCard",
      summary = "Keep the card a setup session saved",
      description =
          "Reads the setup session back from Stripe and keeps its card, so the billing page shows"
              + " it before Stripe's webhook arrives.")
  @ApiResponse(responseCode = "204", description = "Kept.")
  @ApiResponse(
      responseCode = "404",
      description = "The session is not one of this user's.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Stripe has not finished saving the card yet.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/card/confirm", consumes = JSON)
  ResponseEntity<Void> confirmCard(@Valid @RequestBody CardConfirmRequest request);

  @Operation(
      operationId = "removeCard",
      summary = "Remove the saved card",
      description = "Forgets the card and detaches it at Stripe; automatic top-ups turn off.")
  @ApiResponse(responseCode = "204", description = "Removed, or there was none.")
  @DeleteMapping("/card")
  ResponseEntity<Void> removeCard();

  @Operation(
      operationId = "setAutoTopUp",
      summary = "Set automatic top-ups",
      description =
          "While enabled, the saved card is charged amountCents, plus VAT, each time the balance"
              + " drops below belowCents; Stripe issues an invoice for each. A declined card turns"
              + " them off and notifies the user. An admin's card is never charged.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = AutoTopUp.class)))
  @ApiResponse(
      responseCode = "400",
      description = "An amount is outside €5.00 to €1,000.00.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "No card is saved to enable them with.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/auto-top-up", consumes = JSON, produces = JSON)
  ResponseEntity<AutoTopUp> setAutoTopUp(@Valid @RequestBody AutoTopUp request);
}
