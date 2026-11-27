package io.github.kalpak44.workspacedai.api.collaborator;

import io.github.kalpak44.workspacedai.api.auth.Auth0Users;
import io.github.kalpak44.workspacedai.api.auth.AuthProperties;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.MailService;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceDeleted;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Invites users into a workspace by email and lists who is on it. An invitation's link works once,
 * for whoever signs in with the address it was sent to, for {@link #VALID_FOR}. The database keeps
 * neither the address nor the code, only their hashes; addresses shown come from Auth0.
 *
 * <p>Auth0 and Mailjet are called outside every transaction, so a slow one holds no connection.
 */
@Service
public class CollaboratorService {

  static final Duration VALID_FOR = Duration.ofHours(24);
  // Besides the owner. An open invitation holds a seat, so accepting can never go past it.
  static final int MAX_COLLABORATORS = 5;
  // The same address is mailed again only after this, so a double click sends one mail.
  static final Duration RESEND_AFTER = Duration.ofMinutes(1);
  // Withdrawing and re-inviting frees a workspace's slots, so this caps the mail itself, per user.
  static final int MAX_MAILS_PER_DAY = 50;
  // Auth0's Management API is rate limited; addresses almost never change.
  static final Duration EMAIL_TTL = Duration.ofMinutes(10);
  private static final int MAX_CACHED_EMAILS = 10_000;

  private static final Logger LOG = LoggerFactory.getLogger(CollaboratorService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String MARKDOWN = "\\`*_[]<>~|!";

  private record CachedEmail(String email, Instant at) {}

  private final Map<String, CachedEmail> cachedEmails = new ConcurrentHashMap<>();

  private final CollaboratorRepository collaborators;
  private final InvitationRepository invitations;
  private final WorkspaceRepository workspaces;
  private final UserRepository users;
  private final Auth0Users auth0Users;
  private final MailService mail;
  private final NotificationService notifications;
  private final AuthProperties properties;
  private final TransactionTemplate transactions;
  private final ApplicationEventPublisher events;

  CollaboratorService(
      CollaboratorRepository collaborators,
      InvitationRepository invitations,
      WorkspaceRepository workspaces,
      UserRepository users,
      Auth0Users auth0Users,
      MailService mail,
      NotificationService notifications,
      AuthProperties properties,
      TransactionTemplate transactions,
      ApplicationEventPublisher events) {
    this.collaborators = collaborators;
    this.invitations = invitations;
    this.workspaces = workspaces;
    this.users = users;
    this.auth0Users = auth0Users;
    this.mail = mail;
    this.notifications = notifications;
    this.properties = properties;
    this.transactions = transactions;
    this.events = events;
  }

  /**
   * The owner first, then collaborators as they joined, then open invitations as they were sent.
   */
  public List<Collaborator> list(UUID workspaceId) {
    var now = Instant.now();
    var rows =
        transactions.execute(
            _ ->
                new Rows(
                    workspace(workspaceId).getOwnerId(),
                    collaborators.findAllByWorkspaceIdOrderByJoinedAtAscUserIdAsc(workspaceId),
                    invitations.findAllByWorkspaceIdAndExpiresAtAfterOrderByCreatedAtAscIdAsc(
                        workspaceId, now)));
    var emails =
        emails(
            Stream.of(
                    Stream.of(rows.owner()),
                    rows.collaborators().stream()
                        .flatMap(c -> Stream.of(c.getUserId(), c.getInvitedBy())),
                    rows.invitations().stream().map(InvitationEntity::getInvitedBy))
                .flatMap(s -> s)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    var list = new ArrayList<Collaborator>();
    list.add(
        new Collaborator(
            rows.owner(),
            null,
            emails.get(rows.owner()),
            CollaboratorStatus.OWNER,
            null,
            null,
            null));
    for (var c : rows.collaborators()) {
      list.add(
          new Collaborator(
              c.getUserId(),
              null,
              emails.get(c.getUserId()),
              CollaboratorStatus.APPLIED,
              emails.get(c.getInvitedBy()),
              c.getJoinedAt(),
              null));
    }
    for (var i : rows.invitations()) {
      list.add(
          new Collaborator(
              null,
              i.getId(),
              null,
              CollaboratorStatus.PENDING,
              emails.get(i.getInvitedBy()),
              i.getCreatedAt(),
              i.getExpiresAt()));
    }
    return list;
  }

  /**
   * Mails the address a link into the workspace, replacing any earlier invitation to it, whose link
   * then stops working. Anyone on the workspace may invite.
   *
   * @param inviterEmail the inviter's own address, which the mail names as the sender
   * @return the invitation, as {@link #list} shows it
   */
  public Collaborator invite(UUID workspaceId, UUID inviter, String inviterEmail, String email) {
    var address = normalize(email);
    if (address.equals(normalize(inviterEmail))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "you are already on this workspace");
    }
    var members = transactions.execute(_ -> workspaces.findMemberIds(workspaceId));
    if (emails(members).values().stream()
        .map(CollaboratorService::normalize)
        .anyMatch(address::equals)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "that address is already on this workspace");
    }
    var code = newCode();
    var now = Instant.now();
    var sent =
        transactions.execute(
            _ -> {
              // Serializes the inviter's mail, so the daily cap holds across workspaces.
              users.findLockedById(inviter).orElseThrow();
              if (invitations.countMailsSince(inviter, now.minus(Duration.ofDays(1)))
                  >= MAX_MAILS_PER_DAY) {
                throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "you can send at most " + MAX_MAILS_PER_DAY + " invitations a day");
              }
              // Serializes invitations into one workspace, so the caps hold.
              var workspace =
                  workspaces
                      .findLockedById(workspaceId)
                      .orElseThrow(() -> notFound("no workspace " + workspaceId));
              // A free demo is its owner's trial; it is shared only once kept as a paid one.
              if (workspace.isDemo()) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "a demo workspace cannot be shared");
              }
              var invitation =
                  invitations
                      .findByWorkspaceIdAndEmailHash(workspaceId, emailHash(address))
                      .orElse(null);
              if (invitation != null
                  && invitation.getExpiresAt().isAfter(now)
                  && invitation.getCreatedAt().plus(RESEND_AFTER).isAfter(now)) {
                throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "that address was invited a moment ago");
              }
              // Reinviting an address reuses its seat rather than taking another.
              var reused = invitation != null && invitation.getExpiresAt().isAfter(now) ? 1 : 0;
              if (collaborators.countByWorkspaceId(workspaceId)
                      + invitations.countByWorkspaceIdAndExpiresAtAfter(workspaceId, now)
                      - reused
                  >= MAX_COLLABORATORS) {
                throw full();
              }
              if (invitation == null) {
                invitation = new InvitationEntity(workspaceId, emailHash(address));
              }
              invitation.reissue(sha256(code), inviter, now, now.plus(VALID_FOR));
              invitations.recordMail(inviter, now);
              // Named in the mail as the inviter calls it, which only they may have chosen.
              var name =
                  collaborators
                      .findByWorkspaceIdAndUserId(workspaceId, inviter)
                      .map(CollaboratorEntity::getName)
                      .orElse(workspace.getName());
              return new Sent(invitations.saveAndFlush(invitation), name);
            });
    try {
      mail.notify(
          List.of(address),
          "You're invited to " + sent.workspaceName() + " on workspaced",
          invitationText(inviterEmail, sent.workspaceName(), code));
    } catch (RuntimeException e) {
      // Unsent, the link exists nowhere; dropping it lets the inviter try again at once.
      transactions.executeWithoutResult(_ -> invitations.deleteById(sent.invitation().getId()));
      throw e;
    }
    var invitation = sent.invitation();
    notifyInvitee(address, inviterEmail, sent.workspaceName(), invitation.getId());
    return new Collaborator(
        null,
        invitation.getId(),
        null,
        CollaboratorStatus.PENDING,
        inviterEmail,
        invitation.getCreatedAt(),
        invitation.getExpiresAt());
  }

  /**
   * Adds the user to the workspace the code opens, and uses the invitation up. The user must have
   * signed in with the address it was sent to; anyone else is refused, and the link keeps working
   * for the right one.
   */
  public AcceptedInvitation accept(UUID userId, String userEmail, String code) {
    return accept(userId, userEmail, () -> invitations.findLockedByCodeHash(sha256(code.strip())));
  }

  /**
   * As {@link #accept(UUID, String, String)}, by the invitation's id, from the notification an
   * existing account gets. The address is what admits the user: Auth0 has verified it, which the
   * mailed code would only prove again.
   */
  public AcceptedInvitation accept(UUID userId, String userEmail, UUID invitationId) {
    return accept(userId, userEmail, () -> invitations.findLockedById(invitationId));
  }

  private AcceptedInvitation accept(
      UUID userId, String userEmail, Supplier<Optional<InvitationEntity>> find) {
    var now = Instant.now();
    return transactions.execute(
        _ -> {
          var invitation =
              find.get()
                  .filter(i -> i.getExpiresAt().isAfter(now))
                  .orElseThrow(
                      () -> notFound("this invitation has expired or has already been used"));
          if (!invitation.getEmailHash().equals(emailHash(normalize(userEmail)))) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "this invitation was sent to another address; sign in with that one to accept it");
          }
          var workspaceId = invitation.getWorkspaceId();
          // Serializes joining, so two people accepting at once cannot both take the last seat.
          var workspace =
              workspaces
                  .findLockedById(workspaceId)
                  .orElseThrow(() -> notFound("no workspace " + workspaceId));
          var owner = workspace.getOwnerId();
          var joins =
              !owner.equals(userId)
                  && !collaborators.existsByWorkspaceIdAndUserId(workspaceId, userId);
          if (joins && collaborators.countByWorkspaceId(workspaceId) >= MAX_COLLABORATORS) {
            throw full();
          }
          invitations.delete(invitation);
          if (joins) {
            collaborators.save(
                new CollaboratorEntity(
                    workspaceId, userId, invitation.getInvitedBy(), workspace.getName()));
          }
          return new AcceptedInvitation(workspaceId);
        });
  }

  /**
   * Takes a collaborator off the workspace. Only the owner removes others; anyone may remove
   * themselves, which is leaving. Their open pages drop the workspace at once.
   */
  public void remove(UUID workspaceId, UUID userId, UUID caller) {
    transactions.executeWithoutResult(
        _ -> {
          var owner = workspace(workspaceId).getOwnerId();
          if (owner.equals(userId)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "the owner cannot be removed from their workspace");
          }
          if (!owner.equals(caller) && !userId.equals(caller)) {
            throw onlyOwner();
          }
          if (collaborators.deleteByWorkspaceIdAndUserId(workspaceId, userId) == 0) {
            throw notFound("no collaborator " + userId);
          }
          events.publishEvent(new WorkspaceDeleted(workspaceId, userId));
        });
  }

  /**
   * Tells an invitee who already has an account in the app too, where they can accept it. The mail
   * has gone already, so an Auth0 failure here only costs them the shortcut.
   */
  private void notifyInvitee(String address, String inviter, String workspace, UUID invitation) {
    try {
      var message = Notifications.invitation(invitation, inviter, workspace);
      auth0Users.subjectsByEmail(address).stream()
          .map(users::findByIdpSubject)
          .flatMap(Optional::stream)
          .forEach(user -> notifications.send(user.getId(), message));
    } catch (RuntimeException e) {
      LOG.warn("could not notify an invitee in the app", e);
    }
  }

  /** Withdraws an open invitation, whose link then stops working. Only the owner may. */
  public void revoke(UUID workspaceId, UUID invitationId, UUID caller) {
    transactions.executeWithoutResult(
        _ -> {
          if (!workspace(workspaceId).getOwnerId().equals(caller)) {
            throw onlyOwner();
          }
          if (invitations.deleteByIdAndWorkspaceId(invitationId, workspaceId) == 0) {
            throw notFound("no invitation " + invitationId);
          }
        });
  }

  /** Expired invitations open nothing; their hashes are not kept past that. */
  @Scheduled(fixedDelayString = "${workspaced.invitations.cleanup-interval:PT1H}")
  @SchedulerLock(name = "workspace-invitations", lockAtMostFor = "PT5M")
  void deleteExpired() {
    var now = Instant.now();
    transactions.executeWithoutResult(
        _ -> {
          invitations.deleteExpired(now);
          invitations.deleteMailsBefore(now.minus(Duration.ofDays(1)));
        });
  }

  private WorkspaceEntity workspace(UUID id) {
    return workspaces.findById(id).orElseThrow(() -> notFound("no workspace " + id));
  }

  /**
   * Each user's address as Auth0 has it; a user Auth0 no longer knows is left out. Once Auth0 fails
   * a lookup the rest are left out too, rather than failing the page or adding to a rate limit.
   */
  private Map<UUID, String> emails(Collection<UUID> userIds) {
    var subjects =
        users.findAllById(Set.copyOf(userIds)).stream()
            .collect(Collectors.toMap(UserEntity::getId, UserEntity::getIdpSubject));
    var emails = new HashMap<UUID, String>();
    var now = Instant.now();
    var reachable = true;
    for (var entry : subjects.entrySet()) {
      var email = cachedEmail(entry.getValue(), now);
      if (email == null && reachable) {
        try {
          email = auth0Users.email(entry.getValue());
          cacheEmail(entry.getValue(), email, now);
        } catch (RuntimeException e) {
          LOG.warn("could not read collaborators' addresses from Auth0", e);
          reachable = false;
        }
      }
      if (email != null && !email.isBlank()) {
        emails.put(entry.getKey(), email);
      }
    }
    return emails;
  }

  /** Null when not cached, or cached too long ago. */
  private String cachedEmail(String subject, Instant now) {
    var cached = cachedEmails.get(subject);
    return cached != null && cached.at().plus(EMAIL_TTL).isAfter(now) ? cached.email() : null;
  }

  private void cacheEmail(String subject, String email, Instant now) {
    if (cachedEmails.size() >= MAX_CACHED_EMAILS) {
      cachedEmails.clear();
    }
    cachedEmails.put(subject, new CachedEmail(email, now));
  }

  private String invitationText(String inviter, String workspace, String code) {
    var link = properties.appUrl().replaceAll("/+$", "") + "/invitation#" + code;
    return """
        %s invited you to collaborate on the workspace **%s** on workspaced.

        [Accept the invitation](%s)

        The link works once and expires in 24 hours. Accept it with your workspaced account, \
        or create one, signed in as this email address.

        If you weren't expecting this, you can ignore this email: nothing happens unless you \
        accept.
        """
        .formatted(plain(inviter), plain(workspace), link);
  }

  /**
   * Keeps a name the user typed from being read as Markdown, so a workspace name cannot turn into a
   * link in a mail sent from our domain.
   */
  static String plain(String text) {
    var escaped = new StringBuilder(text.length());
    for (var c : text.toCharArray()) {
      if (MARKDOWN.indexOf(c) >= 0) {
        escaped.append('\\');
      }
      escaped.append(c);
    }
    return escaped.toString();
  }

  static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Keyed with the server's secret, so a copy of the table cannot be checked against a list of
   * addresses.
   */
  private String emailHash(String normalized) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal(("invitation:" + normalized).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String newCode() {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static ResponseStatusException notFound(String reason) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
  }

  private static ResponseStatusException full() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT,
        "a workspace can have at most "
            + MAX_COLLABORATORS
            + " collaborators, open invitations included");
  }

  private static ResponseStatusException onlyOwner() {
    return new ResponseStatusException(
        HttpStatus.FORBIDDEN, "only the workspace's owner can do that");
  }

  private record Rows(
      UUID owner, List<CollaboratorEntity> collaborators, List<InvitationEntity> invitations) {}

  private record Sent(InvitationEntity invitation, String workspaceName) {}
}
