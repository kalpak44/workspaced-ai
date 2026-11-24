package io.github.kalpak44.workspacedai.api.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link AccountApi}. */
@RestController
public class AccountController implements AccountApi {

  private final AuthService service;

  public AccountController(AuthService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<Account> get() {
    return ResponseEntity.ok(service.account(CurrentUser.id(), CurrentUser.profile()));
  }

  /** Answers a fresh sign-in cookie with the new names, so every page shows them at once. */
  @Override
  public ResponseEntity<Account> updateProfile(ProfileRequest request) {
    var update =
        service.updateProfile(
            CurrentUser.id(),
            CurrentUser.profile(),
            request.trimmedFirstName(),
            request.trimmedLastName());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, AuthController.cookie(update.token()).toString())
        .body(update.account());
  }

  @Override
  public ResponseEntity<Account> updateMailPreferences(MailPreferences preferences) {
    return ResponseEntity.ok(
        service.updateMailPreferences(CurrentUser.id(), CurrentUser.profile(), preferences));
  }

  @Override
  public ResponseEntity<Void> saveTour(TourProgress progress) {
    service.saveTour(CurrentUser.id(), progress);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> endTour() {
    service.saveTour(CurrentUser.id(), null);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> sendPasswordReset() {
    service.sendPasswordReset(CurrentUser.profile());
    return ResponseEntity.noContent().build();
  }
}
