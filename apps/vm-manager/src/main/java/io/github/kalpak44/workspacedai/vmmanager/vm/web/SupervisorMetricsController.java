package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.SupervisorFleet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Prometheus text only; the private Traefik route exposes this exact path, no VM API. */
@RestController
public class SupervisorMetricsController {
  private final SupervisorFleet fleet;

  public SupervisorMetricsController(SupervisorFleet fleet) {
    this.fleet = fleet;
  }

  @GetMapping(value = "/supervisor-metrics", produces = "text/plain;version=0.0.4")
  public String metrics() {
    return fleet.metrics();
  }
}
