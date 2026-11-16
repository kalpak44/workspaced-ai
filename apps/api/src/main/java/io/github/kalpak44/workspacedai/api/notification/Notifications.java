package io.github.kalpak44.workspacedai.api.notification;

import java.util.UUID;

/** Every notification the app sends, in one place, so their wording stays alike. */
public final class Notifications {

  private static final NotificationAction BILLING =
      new NotificationAction("Go to billing", "/billing");
  private static final NotificationAction ADD_FUNDS =
      new NotificationAction("Add funds", "/billing");

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
          "Your balance could no longer pay for the next hour of "
              + name
              + ", so it was permanently deleted with its files, sessions and secrets. Add funds"
              + " before creating a new workspace.",
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
   */
  public static NotificationMessage fundsRunningOut(String name, long days) {
    var span = days == 0 ? "less than a day" : "about " + days + (days == 1 ? " day" : " days");
    var when = days == 0 ? "within a day" : "in " + span;
    return new NotificationMessage(
        NotificationKind.BILLING,
        days <= 1 ? NotificationTone.ERROR : NotificationTone.WARNING,
        name + " will be deleted " + when,
        "Your balance runs out " + when + ". Add funds to keep " + name + ".",
        "Your balance pays for your workspaces for "
            + span
            + " more. Once it cannot pay the next hour, "
            + name
            + " is permanently deleted with its files, sessions and secrets. Add funds to keep"
            + " it; we remind you each day until then.",
        ADD_FUNDS);
  }
}
