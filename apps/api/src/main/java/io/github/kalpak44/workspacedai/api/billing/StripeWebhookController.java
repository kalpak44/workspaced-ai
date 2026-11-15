package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Stripe reports payments. It signs in as no one, so its signature is the only credential;
 * the body is taken as bytes because the signature covers them exactly. Left out of the API docs,
 * being for Stripe alone.
 */
@Hidden
@RestController
public class StripeWebhookController {

  static final String PATH = "/billing/stripe/webhook";

  private final BillingService billing;

  public StripeWebhookController(BillingService billing) {
    this.billing = billing;
  }

  @PostMapping(PATH)
  public ResponseEntity<Void> receive(
      @RequestBody byte[] payload,
      @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
    return billing.handleEvent(payload, signature)
        ? ResponseEntity.ok().build()
        : ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
  }
}
