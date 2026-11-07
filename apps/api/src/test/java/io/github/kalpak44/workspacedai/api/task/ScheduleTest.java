package io.github.kalpak44.workspacedai.api.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScheduleTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0 9 * * 1-5",
        "*/15 * * * *",
        "0 0 1,15 * *",
        "30 17 * jan-mar fri",
        "0 0 * * 7",
        "@daily"
      })
  void acceptsWhatTheAppAccepts(String cron) {
    assertThat(Schedule.error(cron, "Europe/Sofia")).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "0 9 * *", "0 0 9 * * *", "60 * * * *", "0 9 * 13 *", "@sometimes"})
  void refusesOtherExpressions(String cron) {
    assertThat(Schedule.error(cron, "UTC")).contains("cron expression");
  }

  @Test
  void refusesUnknownTimeZones() {
    assertThat(Schedule.error("@daily", "Mars/Olympus")).contains("time zone");
    assertThat(Schedule.error("@daily", "")).contains("time zone");
  }

  @Test
  void matchesTheMinuteInItsTimeZone() {
    var schedule = Schedule.of("0 9 * * 1-5", "Europe/Sofia").orElseThrow();
    assertThat(schedule.matches(Instant.parse("2026-11-10T07:00:00Z"))).isTrue();
    assertThat(schedule.matches(Instant.parse("2026-11-10T07:01:00Z"))).isFalse();
    assertThat(schedule.matches(Instant.parse("2026-11-10T09:00:00Z"))).isFalse();
    // Summer time: 09:00 in Sofia is 06:00 UTC.
    assertThat(schedule.matches(Instant.parse("2026-07-07T06:00:00Z"))).isTrue();
  }

  @Test
  void readsTheFileItWrites() {
    var task = new TaskFile("pr-summary", "0 9 * * 1-5", "Europe/Sofia", "Line one.\n\nLine two.");
    assertThat(TaskFile.parse("pr-summary", task.toMarkdown())).isEqualTo(task);
  }

  @Test
  void treatsAnUnclosedFrontMatterAsNoSchedule() {
    var task = TaskFile.parse("x", "---\ncron: @daily\nDo it.");
    assertThat(task.cron()).isEmpty();
    assertThat(task.prompt()).isEqualTo("---\ncron: @daily\nDo it.");
  }
}
