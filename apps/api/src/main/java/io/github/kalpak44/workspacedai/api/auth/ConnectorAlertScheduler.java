package io.github.kalpak44.workspacedai.api.auth;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Least-recently-checked keys rotate through a bounded sweep. One bad key does not starve the rest.
 */
@Component
public class ConnectorAlertScheduler {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ConnectorAlertScheduler.class);
  private final AiConnectorRepository connectors;
  private final ConnectorAlerts alerts;
  private final int batchSize;

  public ConnectorAlertScheduler(
      AiConnectorRepository connectors,
      ConnectorAlerts alerts,
      @Value("${workspaced.openrouter.alert-batch-size:50}") int batchSize) {
    this.connectors = connectors;
    this.alerts = alerts;
    this.batchSize = Math.max(1, Math.min(batchSize, 100));
  }

  @Scheduled(
      fixedDelayString = "${workspaced.openrouter.alert-interval:PT1M}",
      initialDelayString = "${workspaced.openrouter.alert-interval:PT1M}")
  @SchedulerLock(name = "ai-connector-alerts", lockAtMostFor = "PT30M")
  public void run() {
    for (var row : connectors.findForStatusCheck(batchSize)) {
      try {
        alerts.check(row.getId());
      } catch (RuntimeException failure) {
        LOG.warn("could not check AI connector {}", row.getId());
      }
    }
  }
}
