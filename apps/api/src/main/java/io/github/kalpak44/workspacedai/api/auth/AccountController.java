package io.github.kalpak44.workspacedai.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Routes, validation and documentation are declared on {@link AccountApi}. */
@RestController
public class AccountController implements AccountApi {

  private final AuthService service;
  private final HttpServletRequest request;

  public AccountController(AuthService service, HttpServletRequest request) {
    this.service = service;
    this.request = request;
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
  public ResponseEntity<Void> changePassword(PasswordRequest body) {
    var token =
        service
            .changePassword(CurrentUser.id(), body.currentPassword(), body.newPassword())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "the current password is wrong"));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, AuthController.cookie(request, token).toString())
        .build();
  }
}
