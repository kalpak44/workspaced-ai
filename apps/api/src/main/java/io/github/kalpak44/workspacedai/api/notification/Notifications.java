package io.github.kalpak44.workspacedai.api.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Every notification the app sends, in one place, so their wording stays alike. */
public final class Notifications {

  private static final NotificationAction BILLING =
      new NotificationAction("Go to billing", "/billing");
  private static final NotificationAction ADD_FUNDS =
      new NotificationAction("Add funds", "/billing");

  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("MMM d 'at' HH:mm 'UTC'", Locale.ENGLISH)
          .withZone(ZoneOffset.UTC);

  private Notifications() {}

  /** For a user just created. */
  public static NotificationMessage welcome(String firstName) {
    var greeting =
        firstName == null || firstName.isBlank() ? "Welcome!" : "Welcome, " + firstName + "!";
    return new NotificationMessage(
        NotificationKind.SYSTEM,
        NotificationTone.INFO,
        "Welcome to workspaced",
        "Take the guided tour: a free demo workspace is waiting for you.",
        greeting
            + " A workspace is a small computer of your own in the cloud, with an AI agent that"
            + " builds and runs things for you. Your first one is free: the guided tour sets up a"
            + " demo workspace, has its agent build a website, and shows you each step on the way."
            + " The VM is free for four hours and then deleted. Your protected Trial connector includes a small one-time AI budget, funded by Workspaced. Connect your own OpenRouter account when you need more AI usage.",
        new NotificationAction("Start the tour", "/?tour"));
  }

  public static NotificationMessage connectorBudget(
      String name, java.math.BigDecimal remaining, java.math.BigDecimal cap, int level) {
    var amount = "$" + remaining.setScale(2, java.math.RoundingMode.DOWN).toPlainString();
    var limit = "$" + cap.setScale(2, java.math.RoundingMode.DOWN).toPlainString();
    var title =
        level == 3
            ? "AI budget used up: " + name
            : level == 2 ? "AI budget almost used up: " + name : "AI budget running low: " + name;
    return new NotificationMessage(
        NotificationKind.SYSTEM,
        level == 1 ? NotificationTone.WARNING : NotificationTone.ERROR,
        title,
        name + " has " + amount + " of its " + limit + " budget remaining.",
        "Your AI connector \""
            + name
            + "\" has "
            + amount
            + " of its "
            + limit
            + " budget remaining. "
            + (level == 3
                ? "AI requests using this key cannot continue until its budget is available again. "
                : "Connect your own funded account before this key's budget runs out. ")
            + "Go to General settings → AI connectors to connect OpenRouter or paste another key. Select the new connector in workspace Settings → Secrets, then restart the workspace to use it.",
        new NotificationAction("Connect your AI account", "/settings/ai-connectors"));
  }

  public static NotificationMessage connectorExpired(String name) {
    return new NotificationMessage(
        NotificationKind.SYSTEM,
        NotificationTone.ERROR,
        "AI connector expired: " + name,
        "The key for " + name + " has expired. Connect your AI account to continue.",
        "Your AI connector \""
            + name
            + "\" has expired. Go to General settings → AI connectors to connect your OpenRouter account or add a valid key. Select it in workspace Settings → Secrets and restart the workspace to continue using AI.",
        new NotificationAction("Connect your AI account", "/settings/ai-connectors"));
  }

  public static NotificationMessage connectorUnavailable(String name) {
    return new NotificationMessage(
        NotificationKind.SYSTEM,
        NotificationTone.ERROR,
        "AI connector needs attention: " + name,
        "OpenRouter no longer accepts the key for " + name + ". Check your connection.",
        "OpenRouter rejected the key for your AI connector \""
            + name
            + "\". It may have expired, been revoked, or been restricted. Go to General settings → AI connectors to connect your account or add a valid key, then select it in workspace Settings → Secrets and restart. This does not mean your workspace has been deleted.",
        new NotificationAction("Connect your AI account", "/settings/ai-connectors"));
  }

  /**
   * @param amount what was added, e.g. 25.00 EUR
   */
  public static NotificationMessage topUpPaid(String amount) {
    return new NotificationMessage(
        NotificationKind.BILLING,
        NotificationTone.SUCCESS,
        "Thank you for your payment",
        amount + " was added to your balance.",
        "We received your payment and added "
            + amount
            + " to your balance. Its invoice is on the billing page.",
        BILLING);
  }

  public static NotificationMessage topUpFailed(String amount) {
    return new NotificationMessage(
        NotificationKind.BILLING,
        NotificationTone.WARNING,
        "Payment failed",
        "A top-up of " + amount + " did not go through.",
        "Your top-up of "
            + amount
            + " was declined and nothing was added to your balance. Try again with another"
            + " payment method, or contact your bank.",
        BILLING);
  }

  public static NotificationMessage topUpExpired(String amount) {
    return new NotificationMessage(
        NotificationKind.BILLING,
        NotificationTone.WARNING,
        "Top-up not completed",
        "A top-up of " + amount + " was never paid.",
        "The payment page for your top-up of "
            + amount
            + " closed before it was paid, so nothing was charged and nothing was added to your"
            + " balance. Start a new top-up whenever you are ready.",
        BILLING);
  }

  /**
   * For an automatic top-up the card was not charged for; automatic top-ups are then off.
   *
   * @param reason Stripe's, e.g. Your card was declined.
   */
  public static NotificationMessage autoTopUpFailed(String amount, String reason) {
    return new NotificationMessage(
        NotificationKind.BILLING,
        NotificationTone.ERROR,
        "Automatic top-up failed",
        amount + " could not be charged to your card. Automatic top-ups are off.",
        "We tried to add "
            + amount
            + " to your balance from your saved card, but it was not charged: "
            + reason
            + " Automatic top-ups are now off, so the card is not tried again. Check the card or"
            + " save another, then turn them back on.",
        BILLING);
  }

  public static NotificationMessage workspaceCreated(UUID id, String name) {
    return new NotificationMessage(
        NotificationKind.WORKSPACE,
        NotificationTone.SUCCESS,
        "Workspace created",
        name + " was created and is starting.",
        "Your workspace "
            + name
            + " was created. Its VM is starting, and the agent is ready for a session in a few"
            + " minutes.",
        new NotificationAction("Open workspace", "/workspaces/" + id));
  }

  /** For a workspace deleted because the balance could not pay its next hour, not by the user. */
  public static NotificationMessage workspaceDeletedUnpaid(String name) {
    return new NotificationMessage(
        NotificationKind.WORKSPACE,
        NotificationTone.ERROR,
        "Workspace deleted",
        name + " was deleted: the balance ran out.",
        name
            + " stayed paused without funds to start it again, so it was permanently deleted"
            + " with its files, sessions and secrets. Add funds before creating a new"
            + " workspace.",
        ADD_FUNDS);
  }

  public static NotificationMessage workspaceDeleted(String name) {
    return new NotificationMessage(
        NotificationKind.WORKSPACE,
        NotificationTone.INFO,
        "Workspace deleted",
        name + " was deleted.",
        "Your workspace "
            + name
            + " was deleted with its VM, files, sessions and secrets. It no longer costs anything.",
        null);
  }

  /** In-app only: the tutorial is finished, but its workspace is still temporary. */
  public static NotificationMessage tutorialCompleted(
      UUID id, String name, Instant expiresAt, String timeZone) {
    var deletion =
        DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm z", Locale.ENGLISH)
            .withZone(ZoneId.of(timeZone))
            .format(expiresAt);
    return new NotificationMessage(
        NotificationKind.WORKSPACE,
        NotificationTone.SUCCESS,
        "Tutorial completed",
        "Your trial is ready. " + name + " will be deleted on " + deletion + ".",
        "You finished the guided WordPress tutorial. You can keep exploring "
            + name
            + " until "
            + deletion
            + ". Then this workspace, its website, files, sessions, secrets"
            + " and scheduled tasks are permanently deleted. Save anything you want to keep before"
            + " then. This trial is not for production. We will notify you before deletion and"
            + " once it has been deleted. OpenRouter bills AI usage separately.",
        new NotificationAction("Open workspace", "/workspaces/" + id));
  }

  /**
   * For an account's free demo, as its VM starts.
   *
   * @param expiresAt when it is deleted
   */
  public static NotificationMessage demoCreated(UUID id, String name, Instant expiresAt) {
    return new NotificationMessage(
        NotificationKind.WORKSPACE,
        NotificationTone.SUCCESS,
        "Your demo workspace is starting",
        name + " is free until " + WHEN.format(expiresAt) + ", then deleted.",
        name
            + " is your free demo: try anything you like in it. It is not charged, and on "
            + WHEN.format(expiresAt)
            + " it is deleted for good with its files, sessions and anything it published, so do"
            + " not keep anything there you need. To keep building afterwards, add funds and"
            + " create a workspace of your own.",
        new NotificationAction("Open workspace", "/workspaces/" + id));
  }

  /**
   * For a demo that is deleted soon.
   *
   * @param minutes whole minutes it has left
   */
  public static NotificationMessage demoEnding(
      UUID id, String name, long minutes, Instant expiresAt) {
    return new NotificationMessage(
        NotificationKind.WORKSPACE,
        NotificationTone.WARNING,
        name + " is deleted in " + minutes + " minutes",
        "It will be deleted on " + WHEN.format(expiresAt) + ". Save anything you want to keep now.",
        "Your free demo "
            + name
            + " is deleted in "
            + minutes
            + " minutes, on "
            + WHEN.format(expiresAt)
            + ", with its website, files, sessions, secrets and scheduled tasks. Download anything"
            + " you want to keep before then. Liked it? Add funds and create a workspace of your"
            + " own to keep building.",
        new NotificationAction("Open workspace", "/workspaces/" + id));
  }

  public static NotificationMessage demoEnded(String name) {
    return new NotificationMessage(
        NotificationKind.WORKSPACE,
        NotificationTone.INFO,
        "Your demo has ended",
        name + " was deleted. Create a workspace of your own to keep building.",
        "Your free demo "
            + name
            + " reached its end and was permanently deleted with its website, files, sessions, secrets and scheduled tasks. Thank you for trying"
            + " workspaced! Add funds and create a workspace of your own whenever you want to keep"
            + " building; your recipes are still here.",
        ADD_FUNDS);
  }

  /** For the author of a recipe that has just turned public for the first time. */
  public static NotificationMessage recipePublished(UUID id, String title) {
    return new NotificationMessage(
        NotificationKind.RECIPE,
        NotificationTone.SUCCESS,
        "Recipe published",
        "“" + title + "” is now public.",
        "Your recipe “"
            + title
            + "” is now public in the store, where anyone can apply and rate it.",
        new NotificationAction("Open recipe", "/store/" + id));
  }

  /**
   * For the author of a recipe someone else has just rated.
   *
   * @param average the mean stars after the rating, to one decimal
   */
  public static NotificationMessage recipeRated(
      UUID id, String title, int stars, double average, int count) {
    var starCount = stars + (stars == 1 ? " star" : " stars");
    return new NotificationMessage(
        NotificationKind.RECIPE,
        NotificationTone.SUCCESS,
        "New rating on your recipe",
        "Someone rated “" + title + "” " + starCount + ".",
        "Your recipe “"
            + title
            + "” received a rating of "
            + starCount
            + ". It now averages "
            + average
            + " from "
            + count
            + (count == 1 ? " rating." : " ratings."),
        new NotificationAction("Open recipe", "/store/" + id));
  }

  /**
   * For a workspace the balance will stop paying for soon.
   *
   * @param days whole days the balance still covers; 0 when less than one
   * @param pausedFor how long it then stays paused before it is deleted
   * @param fee what starting it again once paused costs, e.g. 5.00 EUR
   */
  public static NotificationMessage fundsRunningOut(
      String name, long days, Duration pausedFor, String fee) {
    var span = days == 0 ? "less than a day" : "about " + days + (days == 1 ? " day" : " days");
    var when = days == 0 ? "within a day" : "in " + span;
    return new NotificationMessage(
        NotificationKind.BILLING,
        days <= 1 ? NotificationTone.ERROR : NotificationTone.WARNING,
        name + " will be paused " + when,
        "Your balance runs out "
            + when
            + ". Then starting "
            + name
            + " again costs a "
            + fee
            + " fee, and it is deleted "
            + span(pausedFor)
            + " later.",
        "Your balance pays for your workspaces for "
            + span
            + " more. Once it cannot pay the next hour, "
            + name
            + " is paused: its VM is shut down. Starting it again then costs a "
            + fee
            + " reactivation fee on top of its hours, and if the balance is not topped up within "
            + span(pausedFor)
            + ", it is permanently deleted with its files, sessions and secrets. Add funds now to"
            + " avoid both; we remind you each day until then.",
        ADD_FUNDS);
  }

  /**
   * For a workspace just paused because the balance cannot pay its next hour.
   *
   * @param fee what starting it again costs, e.g. 5.00 EUR
   */
  public static NotificationMessage workspacePaused(String name, Instant deletesAt, String fee) {
    return new NotificationMessage(
        NotificationKind.WORKSPACE,
        NotificationTone.ERROR,
        name + " is paused",
        "The balance ran out. Add funds by " + WHEN.format(deletesAt) + " to keep it.",
        "Your balance can no longer pay for the next hour of "
            + name
            + ", so its VM was shut down and it is no longer charged. Its files, sessions and"
            + " secrets are kept until "
            + WHEN.format(deletesAt)
            + ". Add funds before then and it starts again within a few minutes, with a "
            + fee
            + " reactivation fee taken from the balance. Otherwise it is permanently deleted.",
        ADD_FUNDS);
  }

  /** For a paused workspace, once a day until it is deleted. */
  public static NotificationMessage pausedWorkspaceDeletion(
      String name, Instant deletesAt, Instant now, String fee) {
    var left = Duration.between(now, deletesAt);
    var when = left.toDays() < 1 ? "within a day" : "in " + span(left);
    return new NotificationMessage(
        NotificationKind.BILLING,
        NotificationTone.ERROR,
        name + " will be deleted " + when,
        name + " is paused. Add funds by " + WHEN.format(deletesAt) + " to keep it.",
        name
            + " is paused because the balance ran out. On "
            + WHEN.format(deletesAt)
            + " it is permanently deleted with its files, sessions and secrets. Add funds before"
            + " then and it starts again, with a "
            + fee
            + " reactivation fee taken from the balance.",
        ADD_FUNDS);
  }

  /**
   * For a paused workspace a top-up has started again.
   *
   * @param fee what was taken from the balance to start it, e.g. 5.00 EUR
   */
  public static NotificationMessage workspaceResumed(UUID id, String name, String fee) {
    return new NotificationMessage(
        NotificationKind.WORKSPACE,
        NotificationTone.SUCCESS,
        name + " is running again",
        "Your top-up started it again.",
        "Your balance can pay for "
            + name
            + " again, so its VM is starting with everything it had. A reactivation fee of "
            + fee
            + " was taken from the balance, and its hours are charged again from now.",
        new NotificationAction("Open workspace", "/workspaces/" + id));
  }

  // "about 3 days", in whole days.
  /** For admins, once no plan has been available to create for a while. */
  public static NotificationMessage plansSoldOut(Instant since) {
    return new NotificationMessage(
        NotificationKind.SYSTEM,
        NotificationTone.ERROR,
        "No plan can be created",
        "Every plan has been sold out at Hetzner since " + WHEN.format(since) + ".",
        "Every plan, the fallback plan included, has been sold out at Hetzner in every configured"
            + " location since "
            + WHEN.format(since)
            + ". Until one is back, no one can create a workspace or start the free demo. Adding a"
            + " fallback location or plan to the API's configuration is the only fix on our side;"
            + " you will be told when a plan is available again.",
        null);
  }

  /** For admins, once a plan can be created again after they were told none could. */
  public static NotificationMessage plansBack(Duration outage) {
    var minutes = Math.max(outage.toMinutes(), 1);
    var lasted =
        minutes < 120 ? minutes + " minutes" : "about " + Math.round(minutes / 60.0) + " hours";
    return new NotificationMessage(
        NotificationKind.SYSTEM,
        NotificationTone.SUCCESS,
        "Plans can be created again",
        "A plan is available again after " + lasted + " with none.",
        "Hetzner has a plan's server in stock again, so workspaces and free demos can be created."
            + " No plan was available for "
            + lasted
            + ".",
        null);
  }

  private static String span(Duration duration) {
    var days = Math.max(duration.toDays(), 1);
    return "about " + days + (days == 1 ? " day" : " days");
  }
}
