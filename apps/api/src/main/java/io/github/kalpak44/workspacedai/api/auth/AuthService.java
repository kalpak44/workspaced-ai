package io.github.kalpak44.workspacedai.api.auth;

import io.github.kalpak44.workspacedai.api.mail.MailService;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Turns an Auth0 sign-in into an account here; the tokens themselves come from TokenService. */
@Service
public class AuthService {

  private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

  private final UserRepository users;
  private final TokenService tokens;
  private final NotificationService notifications;
  private final PasswordResets passwordResets;
  private final MailService mail;
  private final AuthProperties properties;

  public AuthService(
      UserRepository users,
      TokenService tokens,
      NotificationService notifications,
      PasswordResets passwordResets,
      MailService mail,
      AuthProperties properties) {
    this.users = users;
    this.tokens = tokens;
    this.notifications = notifications;
    this.passwordResets = passwordResets;
    this.mail = mail;
    this.properties = properties;
  }

  /**
   * @param created whether this sign-in made the account, which the app greets
   */
  public record SignIn(TokenService.Token token, String email, boolean created) {}

  /** The address Auth0 vouches for is the only thing an account is matched or created by. */
  public static class UnverifiedEmailException extends RuntimeException {
    UnverifiedEmailException() {
      super("the email address is not verified");
    }
  }

  /**
   * Finds the account the Auth0 user signs in as, linking an account made before Auth0 by its
   * email, or creates one with a welcome among its notifications.
   */
  @Transactional
  public SignIn signIn(String subject, String email, boolean emailVerified) {
    if (!emailVerified || email == null || email.isBlank()) {
      throw new UnverifiedEmailException();
    }
    var address = email.strip().toLowerCase(Locale.ROOT);
    var existing =
        users
            .findByIdpSubject(subject)
            .or(() -> users.findByEmail(address).filter(u -> u.getIdpSubject() == null));
    var user = existing.orElseGet(() -> create(address));
    user.signedInAs(subject, address);
    return new SignIn(tokens.issue(user), address, existing.isEmpty());
  }

  /**
   * Greets a new account by mail as well. Sent after the sign-in has committed, and a mail that
   * cannot go out is logged rather than turning the sign-in away.
   */
  public void sendWelcomeMail(String email, String appUrl) {
    var text =
        """
        Welcome to workspaced!

        Your account is ready. A workspace is a sandbox with its own AI agent: add funds to \
        your balance, then create a workspace from the sidebar and start a session to give \
        the agent a task.

        [Open workspaced](%s)
        """
            .formatted(appUrl);
    try {
      mail.notify(List.of(email), "Welcome to workspaced", text);
    } catch (RuntimeException e) {
      LOG.warn("the welcome mail to a new account could not be sent", e);
    }
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

  /** Not transactional: the mail is Auth0's call, which no open transaction should wait on. */
  public void sendPasswordReset(UUID userId) {
    passwordResets.send(user(userId).getEmail());
  }

  private UserEntity create(String email) {
    var admin = properties.adminEmails().stream().anyMatch(email::equalsIgnoreCase);
    var role = admin ? Role.ADMIN : Role.USER;
    var user = users.saveAndFlush(new UserEntity(email, "", "", role));
    notifications.send(user.getId(), Notifications.welcome(""));
    return user;
  }

  /** A valid token for an account deleted since it was issued counts as not signed in. */
  private UserEntity user(UUID userId) {
    return users
        .findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in first"));
  }
}
