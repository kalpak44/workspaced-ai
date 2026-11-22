package io.github.kalpak44.workspacedai.api.auth;

import io.github.kalpak44.workspacedai.api.mail.MailService;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import io.github.kalpak44.workspacedai.api.recipe.StarterRecipes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns an Auth0 sign-in into an account here, and serves the account from the session token. The
 * profile is Auth0's, carried in that token, so no page asks Auth0 for it.
 */
@Service
public class AuthService {

  private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

  private final UserRepository users;
  private final TokenService tokens;
  private final JwtDecoder decoder;
  private final NotificationService notifications;
  private final PasswordResets passwordResets;
  private final MailService mail;
  private final Auth0Users auth0Users;
  private final VerificationEmails verificationEmails;
  private final AuthProperties properties;
  private final StarterRecipes starterRecipes;
  private final org.springframework.context.ApplicationEventPublisher events;

  public AuthService(
      UserRepository users,
      TokenService tokens,
      JwtDecoder decoder,
      NotificationService notifications,
      PasswordResets passwordResets,
      MailService mail,
      Auth0Users auth0Users,
      VerificationEmails verificationEmails,
      AuthProperties properties,
      StarterRecipes starterRecipes,
      org.springframework.context.ApplicationEventPublisher events) {
    this.users = users;
    this.tokens = tokens;
    this.decoder = decoder;
    this.notifications = notifications;
    this.passwordResets = passwordResets;
    this.mail = mail;
    this.auth0Users = auth0Users;
    this.verificationEmails = verificationEmails;
    this.properties = properties;
    this.starterRecipes = starterRecipes;
    this.events = events;
  }

  /**
   * @param created whether this sign-in made the account, which the app greets
   */
  public record SignIn(TokenService.Token token, String email, boolean created) {}

  /** An account is only made for an address Auth0 has verified, since the app mails it. */
  public static class UnverifiedEmailException extends RuntimeException {
    UnverifiedEmailException() {
      super("the email address is not verified");
    }
  }

  /**
   * Finds the account the Auth0 user signs in as, or creates one with a welcome among its
   * notifications, and takes the role Auth0 has for them now. The profile goes into the token,
   * never into the database.
   */
  @Transactional
  public SignIn signIn(
      String subject, Auth0Users.Profile profile, boolean emailVerified, Role role) {
    if (!emailVerified || profile.email().isBlank()) {
      throw new UnverifiedEmailException();
    }
    var existing = users.findByIdpSubject(subject);
    var user = existing.orElseGet(() -> create(subject, role));
    user.signedIn(role);
    starterRecipes.claimPublisher(user.getId(), profile.email());
    return new SignIn(tokens.issue(user, profile), profile.email(), existing.isEmpty());
  }

  /**
   * Greets a new account by mail as well. Sent after the sign-in has committed, and a mail that
   * cannot go out is logged rather than turning the sign-in away.
   */
  public void sendWelcomeMail(String email) {
    var text =
        """
        Welcome to workspaced!

        Your account is ready. A workspace is a small computer of your own in the cloud, with \
        an AI agent that builds and runs things for you.

        Your first one is on us: the guided tour sets up a free demo workspace, has its agent \
        build a website and schedule work on it, and explains every step. You only click \
        Next, review the AI-provider notice and choose your WordPress credentials. Your protected \
        Trial AI connector has a small one-time budget, paid by Workspaced, with no automatic refill. \
        The VM is free for four hours and then deleted. Add your own connector to pay for further \
        AI usage through your provider account.

        [Start the tour](%s)
        """
            .formatted(properties.appUrl() + "/?tour");
    try {
      mail.notify(List.of(email), "Welcome to workspaced", text);
    } catch (RuntimeException e) {
      // The exception alone: a mail service's message can repeat the recipient's address.
      LOG.warn("the welcome mail to a new account could not be sent: {}", e.getClass().getName());
    }
  }

  /** The account, its profile from the session token and its role from the database. */
  public Account account(UUID userId, Auth0Users.Profile profile) {
    return Account.of(user(userId), profile);
  }

  /** Null ends the tour. */
  @Transactional
  public void saveTour(UUID userId, TourProgress progress) {
    user(userId).setTourProgress(progress);
  }

  public record ProfileUpdate(Account account, TokenService.Token token) {}

  /**
   * Saves the names to Auth0 and answers a session token carrying them, so the change shows at
   * once. Not transactional: no connection waits on Auth0.
   */
  public ProfileUpdate updateProfile(
      UUID userId, Auth0Users.Profile profile, String firstName, String lastName) {
    var user = user(userId);
    auth0Users.updateNames(user.getIdpSubject(), firstName, lastName);
    var updated = new Auth0Users.Profile(profile.email(), firstName, lastName, profile.picture());
    return new ProfileUpdate(Account.of(user, updated), tokens.issue(user, updated));
  }

  public void sendPasswordReset(Auth0Users.Profile profile) {
    passwordResets.send(profile.email());
  }

  /**
   * Revokes every session of the account the token belongs to, on every device. A token that is
   * expired, altered or no sign-in at all revokes nothing; signing out still clears the cookie.
   */
  @Transactional
  public void signOut(String sessionToken) {
    try {
      var jwt = decoder.decode(sessionToken);
      if (!jwt.hasClaim(CurrentUser.USE)) {
        users
            .findById(UUID.fromString(jwt.getSubject()))
            .ifPresent(user -> user.signOutEverywhere(Instant.now()));
      }
    } catch (JwtException | IllegalArgumentException _) {
      // Nothing to revoke.
    }
  }

  /** What an unverified sign-up carries to ask for the email again. */
  public TokenService.Token verificationToken(String idpSubject) {
    return tokens.issueVerification(idpSubject);
  }

  /**
   * Has Auth0 send the verification email again for the sign-up the token names, at most once a
   * minute. The claim commits before Auth0 is asked, so no connection waits on it.
   */
  public void resendVerificationEmail(String token) {
    var subject = verifiedSubject(token);
    boolean claimed;
    try {
      claimed = verificationEmails.claim(subject);
    } catch (DataIntegrityViolationException _) {
      // Another instance inserted the first send at the same moment.
      claimed = false;
    }
    if (!claimed) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "wait a minute before asking again");
    }
    auth0Users.sendVerificationEmail(subject);
  }

  private String verifiedSubject(String token) {
    try {
      var jwt = decoder.decode(token);
      if (CurrentUser.VERIFY_EMAIL.equals(jwt.getClaimAsString(CurrentUser.USE))) {
        return jwt.getSubject();
      }
    } catch (JwtException _) {
      // Expired, altered or not ours: the same answer as none at all.
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in again");
  }

  private UserEntity create(String subject, Role role) {
    var user = users.saveAndFlush(new UserEntity(subject, role));
    notifications.send(user.getId(), Notifications.welcome(""));
    events.publishEvent(new UserCreated(user.getId()));
    return user;
  }

  /** A valid token for an account deleted since it was issued counts as not signed in. */
  private UserEntity user(UUID userId) {
    return users
        .findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in first"));
  }
}
