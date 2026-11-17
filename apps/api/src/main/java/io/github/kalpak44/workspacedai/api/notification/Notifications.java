package io.github.kalpak44.workspacedai.api.notification;

import java.time.Duration;
import java.time.Instant;
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
        "Add funds, then create a workspace to get your first agent.",
        greeting
            + " A workspace is a sandbox with its own agent. Add funds to your balance first,"
            + " then create one from the sidebar and start a session to give the agent a task.",
        BILLING);
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

  /**
   * @param unpaid deleted because the balance could not pay its next hour, not by the user
   */
  public static NotificationMessage workspaceDeleted(String name, boolean unpaid) {
    if (unpaid) {
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
   */
  public static NotificationMessage fundsRunningOut(String name, long days, Duration pausedFor) {
    var span = days == 0 ? "less than a day" : "about " + days + (days == 1 ? " day" : " days");
    var when = days == 0 ? "within a day" : "in " + span;
    return new NotificationMessage(
        NotificationKind.BILLING,
        days <= 1 ? NotificationTone.ERROR : NotificationTone.WARNING,
        name + " will be paused " + when,
        "Your balance runs out " + when + ". Add funds to keep " + name + " running.",
        "Your balance pays for your workspaces for "
            + span
            + " more. Once it cannot pay the next hour, "
            + name
            + " is paused, and permanently deleted with its files, sessions and secrets "
            + span(pausedFor)
            + " later unless you add funds. We remind you each day until then.",
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
  private static String span(Duration duration) {
    var days = Math.max(duration.toDays(), 1);
    return "about " + days + (days == 1 ? " day" : " days");
  }
}
