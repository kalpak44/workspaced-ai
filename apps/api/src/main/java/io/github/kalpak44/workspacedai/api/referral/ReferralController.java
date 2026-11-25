package io.github.kalpak44.workspacedai.api.referral;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes and documentation are declared on {@link ReferralApi}. */
@RestController
public class ReferralController implements ReferralApi {

  private final ReferralService service;

  public ReferralController(ReferralService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<Referrals> get() {
    return ResponseEntity.ok(service.overview(CurrentUser.id(), CurrentUser.admin()));
  }
}
