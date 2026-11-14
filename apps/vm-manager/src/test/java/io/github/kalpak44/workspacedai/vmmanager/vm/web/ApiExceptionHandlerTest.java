package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.vmmanager.vm.HarnessNotConfiguredException;
import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {

  // A deployment with no image is this deployment's fault, as with a missing Hetzner token.
  @Test
  void answersUnavailableWithoutAHarnessImage() {
    var problem =
        new ApiExceptionHandler().harnessNotConfigured(new HarnessNotConfiguredException());

    assertThat(problem.getStatus()).isEqualTo(503);
    assertThat(problem.getDetail()).isEqualTo("no harness image is configured");
  }
}
