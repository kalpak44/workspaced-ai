package io.github.kalpak44.workspacedai.api.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Signs users in and changes their passwords; the tokens themselves come from TokenService. */
@Service
public class AuthService {

  private final AuthenticationManager authentication;
  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final TokenService tokens;

  public AuthService(
      AuthenticationManager authentication,
      UserRepository users,
      PasswordEncoder passwords,
      TokenService tokens) {
    this.authentication = authentication;
    this.users = users;
    this.passwords = passwords;
    this.tokens = tokens;
  }

  public record SignIn(Account account, TokenService.Token token) {}

  /** One answer for an unknown user and a wrong password, so usernames cannot be probed. */
  @Transactional(readOnly = true)
  public SignIn login(String username, String password) {
    try {
      authentication.authenticate(
          UsernamePasswordAuthenticationToken.unauthenticated(username, password));
    } catch (AuthenticationException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wrong username or password", e);
    }
    var user = users.findByUsername(username).orElseThrow();
    return new SignIn(Account.of(user), tokens.issue(user));
  }

  @Transactional(readOnly = true)
  public Account account(UUID userId) {
    return Account.of(user(userId));
  }

  @Transactional
  public Account updateProfile(UUID userId, String firstName, String lastName, String avatar) {
    var user = user(userId);
    user.setProfile(firstName, lastName, avatar);
    return Account.of(user);
  }

  /**
   * Every token issued before stops working, so this answers a new one for the caller; empty when
   * the current password is wrong, and nothing is changed then.
   */
  @Transactional
  public Optional<TokenService.Token> changePassword(
      UUID userId, String currentPassword, String newPassword) {
    var user = user(userId);
    if (!passwords.matches(currentPassword, user.getPasswordHash())) {
      return Optional.empty();
    }
    user.setPasswordHash(passwords.encode(newPassword));
    return Optional.of(tokens.issue(user));
  }

  /** A valid token for an account deleted since it was issued counts as not signed in. */
  private UserEntity user(UUID userId) {
    return users
        .findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in first"));
  }
}
