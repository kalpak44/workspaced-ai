package io.github.kalpak44.workspacedai.api.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Routes, validation and documentation are declared on {@link AccountApi}. */
@RestController
public class AccountController implements AccountApi {

  private final AuthService service;

  public AccountController(AuthService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<Account> get() {
    return ResponseEntity.ok(service.account(CurrentUser.id()));
  }

  @Override
  public ResponseEntity<Account> updateProfile(ProfileRequest request) {
    return ResponseEntity.ok(
        service.updateProfile(
            CurrentUser.id(),
            request.trimmedFirstName(),
            request.trimmedLastName(),
            request.avatarOrNull()));
  }

  @Override
  public ResponseEntity<Void> changePassword(PasswordRequest request) {
    if (!service.changePassword(
        CurrentUser.id(), request.currentPassword(), request.newPassword())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "the current password is wrong");
    }
    return ResponseEntity.noContent().build();
  }
}
