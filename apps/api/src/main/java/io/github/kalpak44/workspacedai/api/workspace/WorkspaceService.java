package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.billing.BillingService;
import io.github.kalpak44.workspacedai.api.compute.ComputeProvider;
import io.github.kalpak44.workspacedai.api.compute.ServerTypeRejectedException;
import io.github.kalpak44.workspacedai.api.compute.VmPricing;
import io.github.kalpak44.workspacedai.api.compute.VmSize;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.notification.NotificationMessage;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceEnvironment;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceTerminal;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Keeps the stored workspace and its runtime together. The row is committed before the runtime is
 * asked for a VM, so that slow call holds no database connection; any failure after that removes
 * both again, so a failed create leaves nothing behind to bill or to list. The other slow calls
 * (checking the provider's key, waiting for a VM to be deleted) run outside any transaction too.
 *
 * <p>A demo is an account's one free workspace: the cheapest available size, never charged, and
 * deleted by {@link DemoReaper} once its time is up. The account's claim on it is taken before the
 * VM is asked for and handed back only when that start fails.
 */
@Service
public class WorkspaceService {

  private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final WorkspaceRepository repository;
  private final WorkspaceRuntime runtime;
  private final ApplicationEventPublisher events;
  private final ApiKeyCheck keyCheck;
  private final Harness harness;
  private final TransactionTemplate transactions;
  private final UserRepository users;
  private final VmPricing pricing;
  private final BillingService billing;
  private final NotificationService notifications;
  private final Duration demoLength;
  private final int demosAtOnce;

  public WorkspaceService(
      WorkspaceRepository repository,
      WorkspaceRuntime runtime,
      ApplicationEventPublisher events,
      ApiKeyCheck keyCheck,
      Harness harness,
      TransactionTemplate transactions,
      UserRepository users,
      VmPricing pricing,
      BillingService billing,
      NotificationService notifications,
      @Value("${workspaced.demo.length:PT4H}") Duration demoLength,
      @Value("${workspaced.demo.at-once:20}") int demosAtOnce) {
    this.repository = repository;
    this.runtime = runtime;
    this.events = events;
    this.keyCheck = keyCheck;
    this.harness = harness;
    this.transactions = transactions;
    this.users = users;
    this.pricing = pricing;
    this.billing = billing;
    this.notifications = notifications;
    this.demoLength = demoLength;
    this.demosAtOnce = demosAtOnce;
  }

  /** The workspaces one user owns; nobody sees another user's, and drafts are not listed. */
  @Transactional(readOnly = true)
  public List<Workspace> list(UUID ownerId) {
    var entities = repository.findAllByOwnerIdAndDraftFalseOrderByCreatedAtAscIdAsc(ownerId);
    var statuses = runtime.statuses(entities.stream().map(WorkspaceEntity::getId).toList());
    return entities.stream().map(e -> Workspace.of(e, statuses.get(e.getId()))).toList();
  }

  /** A workspace the owner's balance pays for, as {@link #create} with demo false makes one. */
  public Workspace create(
      UUID ownerId,
      String name,
      ModelProvider provider,
      String apiKey,
      String serverType,
      String timeZone) {
    return create(ownerId, name, provider, apiKey, serverType, timeZone, false);
  }

  /**
   * The key is checked before anything is stored, so a rejected key leaves nothing behind. The
   * workspace receives it as a secret and the base URL as config; neither is kept in the database.
   * The harness gets a password of its own, which only the API and the workspace ever see.
   *
   * @param serverType null for the default size, ignored for a demo, which chooses the cheapest
   *     available size
   * @param timeZone an IANA zone, which the harness always starts with
   * @param demo the account's one free demo, rather than a workspace its balance pays for
   */
  public Workspace create(
      UUID ownerId,
      String name,
      ModelProvider provider,
      String apiKey,
      String serverType,
      String timeZone,
      boolean demo) {
    validTimeZone(timeZone);
    var size = demo ? cheapestDemoSize() : serverType;
    var price = demo ? Optional.<BigDecimal>empty() : priceFor(ownerId, size);
    price.ifPresent(monthly -> billing.requireMonthOf(ownerId, monthly));
    if (demo) {
      requireDemoAvailable(ownerId);
    }
    if (!keyCheck.accepts(provider, apiKey)) {
      throw new ApiKeyRejectedException(provider);
    }
    if (demo) {
      claimDemo(ownerId);
    }
    var created =
        transactions.execute(
            _ ->
                repository.saveAndFlush(
                    new WorkspaceEntity(ownerId, name, provider.id(), timeZone, demo)));
    var id = created.getId();
    try {
      return start(
          id,
          ownerId,
          name,
          provider,
          created.getComputeProvider(),
          apiKey,
          size,
          timeZone,
          price,
          demo);
    } catch (RuntimeException e) {
      // The user sees only that it failed; this is where the reason is kept.
      log.warn("workspace {} failed to create", id, e);
      release(id);
      transactions.executeWithoutResult(_ -> repository.deleteById(id));
      if (demo) {
        users.releaseDemo(ownerId);
      }
      throw e;
    }
  }

  /**
   * A workspace with an id but no VM, for a key that is issued only once the id exists. It is
   * checked as {@link #create} checks one, so its launch fails only on the key or on Hetzner. An
   * account has one demo draft at most: a tour started again, in another browser say, gets the one
   * it already has back, renamed, rather than a second free workspace.
   *
   * @param serverType null for the default size
   */
  public Workspace createDraft(
      UUID ownerId,
      String name,
      ModelProvider provider,
      String serverType,
      String timeZone,
      boolean demo) {
    validTimeZone(timeZone);
    var size = demo ? cheapestDemoSize() : serverType;
    if (demo) {
      requireDemoAvailable(ownerId);
    } else {
      priceFor(ownerId, size).ifPresent(monthly -> billing.requireMonthOf(ownerId, monthly));
    }
    var entity =
        transactions.execute(
            _ -> {
              if (demo) {
                users.lockById(ownerId).orElseThrow();
                var existing =
                    repository.findFirstByOwnerIdAndModelProviderAndDemoTrueAndDraftTrue(
                        ownerId, provider.id());
                if (existing.isPresent()) {
                  var draft = existing.get();
                  draft.setName(name);
                  draft.setTimeZone(timeZone);
                  return repository.saveAndFlush(draft);
                }
              }
              return repository.saveAndFlush(
                  WorkspaceEntity.draft(ownerId, name, provider.id(), size, timeZone, demo));
            });
    return Workspace.of(entity, WorkspaceStatus.DRAFT);
  }

  /**
   * Creates a draft's VM with its key, as {@link #create} would have. A failed launch leaves it a
   * draft, so the same id can be launched again. Empty when no workspace has that id.
   */
  public Optional<Workspace> launch(UUID id, String apiKey) {
    var found = repository.findById(id);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    var draft = found.get();
    if (!draft.isDraft()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "the workspace is already launched");
    }
    var ownerId = draft.getOwnerId();
    var demo = draft.isDemo();
    var provider = ModelProvider.byId(draft.getModelProvider()).orElseThrow();
    var size = demo ? cheapestDemoSize() : draft.getServerType();
    var price = demo ? Optional.<BigDecimal>empty() : priceFor(ownerId, size);
    price.ifPresent(monthly -> billing.requireMonthOf(ownerId, monthly));
    if (demo) {
      requireDemoAvailable(ownerId);
    }
    if (!keyCheck.accepts(provider, apiKey)) {
      throw new ApiKeyRejectedException(provider);
    }
    if (demo) {
      claimDemo(ownerId);
    }
    // Claimed before the VM is asked for, so two launches at once start one VM between them.
    if (repository.claimDraft(id) == 0) {
      if (demo) {
        users.releaseDemo(ownerId);
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT, "the workspace is already launched");
    }
    try {
      return Optional.of(
          start(
              id,
              ownerId,
              draft.getName(),
              provider,
              draft.getComputeProvider(),
              apiKey,
              size,
              draft.getTimeZone(),
              price,
              demo));
    } catch (RuntimeException e) {
      log.warn("workspace {} failed to launch", id, e);
      release(id);
      repository.returnToDraft(id);
      if (demo) {
        users.releaseDemo(ownerId);
      }
      throw e;
    }
  }

  private Workspace start(
      UUID id,
      UUID ownerId,
      String name,
      ModelProvider provider,
      ComputeProvider compute,
      String apiKey,
      String serverType,
      String timeZone,
      Optional<BigDecimal> price,
      boolean demo) {
    var startedAs =
        runtime.start(
            id,
            ownerId,
            compute,
            serverType,
            new WorkspaceEnvironment(
                Map.of(
                    provider.baseUrlVariable(),
                    provider.baseUrl(),
                    Harness.TIME_ZONE_VARIABLE,
                    timeZone),
                secrets(provider, apiKey)));
    price.ifPresent(monthly -> billing.startCharging(id, ownerId, monthly));
    var entity =
        transactions.execute(
            _ -> {
              var saved = repository.findById(id).orElseThrow();
              saved.setServerType(startedAs);
              if (demo) {
                var expiresAt = Instant.now().plus(demoLength);
                saved.setExpiresAt(expiresAt);
                notifications.send(ownerId, Notifications.demoCreated(id, name, expiresAt));
              } else {
                notifications.send(ownerId, Notifications.workspaceCreated(id, name));
              }
              return saved;
            });
    return Workspace.of(entity, statusOf(id));
  }

  // Checked before the provider's key, so an account that cannot have a demo is told why rather
  // than that its key was rejected; the claim itself is taken only once the key is accepted.
  private void requireDemoAvailable(UUID ownerId) {
    var available = users.findById(ownerId).map(UserEntity::isDemoAvailable).orElse(false);
    if (!available) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "this account has had its demo");
    }
    if (repository.countByDemoTrueAndDraftFalse() >= demosAtOnce) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "every demo is taken right now; try again later");
    }
  }

  private String cheapestDemoSize() {
    return pricing.sizes(true).stream()
        .filter(VmSize::available)
        .min(Comparator.comparing(VmSize::monthlyGross).thenComparing(VmSize::serverType))
        .map(VmSize::serverType)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "no demo VM size is available right now; try again later"));
  }

  private void claimDemo(UUID ownerId) {
    if (users.claimDemo(ownerId, Instant.now()) == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "this account has had its demo");
    }
  }

  /**
   * What the owner pays a month for the size; nothing for an admin, whose workspaces are not
   * charged.
   */
  private Optional<BigDecimal> priceFor(UUID ownerId, String serverType) {
    var admin = users.findById(ownerId).map(user -> user.getRole() == Role.ADMIN).orElse(false);
    if (admin) {
      return Optional.empty();
    }
    return Optional.of(
        pricing
            .monthlyPrice(serverType)
            .orElseThrow(
                () ->
                    new ServerTypeRejectedException(
                        "server type " + serverType + " is not one /vm-sizes lists")));
  }

  // Best effort: when the runtime cannot be reached either, a VM keeps its workspace-id label and
  // is found by it.
  private void release(UUID workspaceId) {
    try {
      runtime.stop(workspaceId);
    } catch (RuntimeException e) {
      log.error("workspace {} failed to create and could not be released", workspaceId, e);
    }
  }

  private static Map<String, String> secrets(ModelProvider provider, String apiKey) {
    return Map.of(provider.apiKeyVariable(), apiKey, Harness.PASSWORD_VARIABLE, password());
  }

  private static String password() {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @Transactional
  public Optional<Workspace> rename(UUID id, String name) {
    return repository
        .findById(id)
        .map(
            entity -> {
              entity.setName(name);
              return Workspace.of(entity, statusOf(id));
            });
  }

  /**
   * The VM gets the zone before the row does, so a failed call leaves both as they were; the
   * harness reads it only from its next start, so the workspace is left needing a restart. Empty
   * when no workspace has that id.
   */
  @Transactional
  public Optional<Workspace> setTimeZone(UUID id, String timeZone) {
    validTimeZone(timeZone);
    var entity = repository.findById(id);
    if (entity.isEmpty()) {
      return Optional.empty();
    }
    var workspace = entity.get();
    if (!workspace.getTimeZone().equals(timeZone)) {
      try {
        runtime.setSystemVariable(id, Harness.TIME_ZONE_VARIABLE, timeZone);
      } catch (IllegalStateException e) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "the workspace has no VM yet", e);
      }
      workspace.setTimeZone(timeZone);
      workspace.setSecretsPendingRestart(true);
    }
    return Optional.of(Workspace.of(workspace, statusOf(id)));
  }

  // Only region names: an offset such as +02:00 would freeze daylight saving out.
  private static void validTimeZone(String timeZone) {
    if (timeZone == null || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, timeZone + " is not a time zone such as Europe/Sofia");
    }
  }

  /**
   * Returns once the workspace's runtime is gone, so the caller can show it deleting until then.
   * The runtime is stopped first, outside any transaction, since that waits for its VM; if it does
   * not stop the row is kept, so the delete can be retried. False when no workspace has that id.
   */
  public boolean delete(UUID id) {
    return delete(id, false, Notifications::workspaceDeleted);
  }

  /**
   * Deletes it as {@link #delete(UUID)} does, for a workspace left paused until its owner's balance
   * could no longer keep it; the owner is told that was why.
   */
  public boolean deleteUnpaid(UUID id) {
    return delete(id, true, Notifications::workspaceDeletedUnpaid);
  }

  /** Records completion once, using a row lock so concurrent retries cannot duplicate it. */
  @Transactional
  public boolean completeTutorial(UUID id) {
    var found = repository.lockById(id);
    if (found.isEmpty()) return false;
    var workspace = found.get();
    if (!workspace.isDemo()
        || workspace.isDraft()
        || workspace.getExpiresAt() == null
        || !workspace.getExpiresAt().isAfter(Instant.now())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "the tutorial requires a running free trial");
    }
    notifications.sendOnce(
        workspace.getOwnerId(),
        "tutorial-completed",
        Notifications.tutorialCompleted(
            id, workspace.getName(), workspace.getExpiresAt(), workspace.getTimeZone()));
    return true;
  }

  /** Deletes a demo whose time is up; the owner is told that was why. */
  public boolean deleteExpiredDemo(UUID id) {
    return delete(id, false, Notifications::demoEnded);
  }

  /**
   * @param mail whether the owner is mailed the reason as well as notified
   */
  private boolean delete(UUID id, boolean mail, Function<String, NotificationMessage> reason) {
    var owner = repository.findOwnerIdById(id);
    if (owner.isEmpty()) {
      return false;
    }
    // A draft has no VM to stop and was never announced, so it goes without a notification.
    if (repository.findById(id).map(WorkspaceEntity::isDraft).orElse(false)) {
      repository.deleteById(id);
      return true;
    }
    runtime.stop(id);
    transactions.executeWithoutResult(
        _ -> {
          repository
              .findById(id)
              .ifPresent(
                  workspace -> {
                    var note = reason.apply(workspace.getName());
                    if (mail) {
                      notifications.sendAndMail(owner.get(), note);
                    } else {
                      notifications.send(owner.get(), note);
                    }
                  });
          repository.deleteById(id);
          events.publishEvent(new WorkspaceDeleted(id, owner.get()));
        });
    return true;
  }

  /**
   * Shuts its VM down, keeping its files, for an owner whose balance cannot pay its next hour.
   * False when no workspace has that id.
   */
  public boolean pause(UUID id) {
    if (!repository.existsById(id)) {
      return false;
    }
    runtime.pause(id);
    return true;
  }

  /** Starts a paused one again once its owner can pay. False when no workspace has that id. */
  public boolean resume(UUID id) {
    if (!repository.existsById(id)) {
      return false;
    }
    runtime.resume(id);
    return true;
  }

  /**
   * Returns once the reboot is under way; the status stream reports it starting and then ready. It
   * reads the secrets afresh, so none is left pending. False when no workspace has that id.
   */
  @Transactional
  public boolean restart(UUID id) {
    var entity = repository.findById(id);
    if (entity.isEmpty()) {
      return false;
    }
    if (statusOf(id) == WorkspaceStatus.PAUSED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "the workspace is paused until the balance is topped up");
    }
    entity.get().setSecretsPendingRestart(false);
    try {
      runtime.restart(id);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "workspace cannot be restarted", e);
    }
    return true;
  }

  /** Opens a terminal only for a workspace that still exists in this application. */
  public WorkspaceTerminal terminal(UUID id, OutputStream output, Runnable onExit) {
    if (!repository.existsById(id)) {
      throw new IllegalArgumentException("no workspace " + id);
    }
    var endpoint =
        runtime
            .harness(id)
            .orElseThrow(() -> new IllegalStateException("workspace OpenCode is not running"));
    return harness.terminal(endpoint, Harness.PROJECT_ROOT, output, onExit);
  }

  /** Empty when no workspace has that id. */
  @Transactional(readOnly = true)
  public Optional<UUID> ownerOf(UUID id) {
    return repository.findOwnerIdById(id);
  }

  private WorkspaceStatus statusOf(UUID id) {
    return runtime.statuses(Set.of(id)).get(id);
  }
}
