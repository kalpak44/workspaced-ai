package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes and documentation are declared on {@link BillingApi}. */
@RestController
public class BillingController implements BillingApi {

  private final BillingService billing;

  public BillingController(BillingService billing) {
    this.billing = billing;
  }

  @Override
  public ResponseEntity<Billing> get() {
    return ResponseEntity.ok(billing.billing(CurrentUser.id()));
  }

  @Override
  public ResponseEntity<Checkout> startTopUp(TopUpRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(billing.startTopUp(CurrentUser.id(), request.amountCents()));
  }

  @Override
  public ResponseEntity<TopUp> refresh(UUID id) {
    return ResponseEntity.ok(billing.refresh(CurrentUser.id(), id));
  }

  @Override
  public ResponseEntity<Void> invoice(UUID id) {
    return ResponseEntity.status(HttpStatus.SEE_OTHER)
        .location(billing.invoice(CurrentUser.id(), id))
        .build();
  }
}
