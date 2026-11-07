package io.github.kalpak44.workspacedai.api.task;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.springframework.scheduling.support.CronExpression;

/** A task's cron expression read in its time zone. */
record Schedule(CronExpression cron, ZoneId zone) {

  /** Empty when either is not valid, with no reason; {@link #error} gives one. */
  static Optional<Schedule> of(String cron, String timeZone) {
    return error(cron, timeZone) == null
        ? Optional.of(new Schedule(expression(cron), ZoneId.of(timeZone.strip())))
        : Optional.empty();
  }

  /** Why the cron expression or the time zone is not valid, or null when both are. */
  static String error(String cron, String timeZone) {
    try {
      expression(cron);
    } catch (IllegalArgumentException e) {
      return "the cron expression is not valid: use five fields (minute, hour, day of month, month,"
          + " day of week) or a macro such as @daily";
    }
    try {
      ZoneId.of(timeZone == null ? "" : timeZone.strip());
    } catch (DateTimeException e) {
      return "the time zone is not valid: use one such as Europe/Sofia";
    }
    return null;
  }

  /** Whether the expression matches the minute that starts at {@code minute}. */
  boolean matches(Instant minute) {
    var at = minute.atZone(zone);
    var next = cron.next(at.minusSeconds(1));
    return next != null && next.toInstant().equals(minute);
  }

  /** Spring's expressions start with seconds; a task's do not, so it runs at the minute's start. */
  private static CronExpression expression(String cron) {
    var text = cron == null ? "" : cron.strip();
    if (text.startsWith("@")) {
      return CronExpression.parse(text.toLowerCase(Locale.ROOT));
    }
    if (text.split("\\s+").length != 5) {
      throw new IllegalArgumentException("not five fields: " + text);
    }
    return CronExpression.parse("0 " + text.toUpperCase(Locale.ROOT));
  }
}
