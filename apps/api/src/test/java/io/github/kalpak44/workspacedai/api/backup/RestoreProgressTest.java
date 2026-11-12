package io.github.kalpak44.workspacedai.api.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RestoreProgressTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void readsTheLastReportInTheLog() {
    var log =
        List.of(
            "workspace-backup: restoring r1",
            "workspace-backup progress {\"phase\": \"downloading\", \"done\": 5, \"total\": 10}",
            "workspace-backup progress {\"phase\": \"extracting\", \"done\": 50, \"total\": 100}",
            "something else the start script printed");
    assertThat(BackupService.latestRestoreProgress(log, JSON))
        .contains(new RestoreProgress("extracting", 50, 100, 64));
  }

  @Test
  void isEmptyForAnOrdinaryStartOrAnUnreadableReport() {
    assertThat(BackupService.latestRestoreProgress(List.of("server listening"), JSON)).isEmpty();
    assertThat(BackupService.latestRestoreProgress(List.of(), JSON)).isEmpty();
    assertThat(
            BackupService.latestRestoreProgress(List.of("workspace-backup progress {oops"), JSON))
        .isEmpty();
  }

  @Test
  void spreadsTheBarAcrossThePhases() {
    assertThat(BackupService.overall("downloading", 1, 2)).isEqualTo(10);
    assertThat(BackupService.overall("checking", 1, 2)).isEqualTo(25);
    assertThat(BackupService.overall("extracting", 0, 10)).isEqualTo(30);
    assertThat(BackupService.overall("done", 1, 1)).isEqualTo(100);
    assertThat(BackupService.overall("unknown", 1, 1)).isZero();
  }
}
