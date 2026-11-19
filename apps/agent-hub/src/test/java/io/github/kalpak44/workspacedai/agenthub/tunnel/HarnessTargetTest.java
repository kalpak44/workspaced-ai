package io.github.kalpak44.workspacedai.agenthub.tunnel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class HarnessTargetTest {

  private static final UUID VM = UUID.fromString("1b4e28ba-2fa1-11d2-883f-0016d3cca427");

  @Test
  void readsTheVmAndWholePathFromTheRequest() {
    var target =
        HarnessTarget.of(request("/internal/vms/" + VM + "/harness/api/session", "x=1&y=%2F"));

    assertThat(target.vmId()).isEqualTo(VM);
    assertThat(target.path()).isEqualTo("/api/session?x=1&y=%2F");
  }

  @Test
  void prefersTheVmBoundByTheControllersRoute() {
    var target =
        HarnessTarget.of(request("/internal/vms/not-a-uuid/harness/api/info", null), VM.toString());

    assertThat(target.vmId()).isEqualTo(VM);
    assertThat(target.path()).isEqualTo("/api/info");
  }

  @Test
  void treatsAnEmptyPathAsTheHarnessRoot() {
    assertThat(HarnessTarget.of(request("/internal/vms/" + VM + "/harness", null)).path())
        .isEqualTo("/");
    assertThat(HarnessTarget.of(request("/internal/vms/" + VM + "/harness", "x=1")).path())
        .isEqualTo("/?x=1");
  }

  @Test
  void refusesARequestOutsideTheHarnessPrefix() {
    assertNotFound(request("/elsewhere/" + VM + "/harness", null));
  }

  @Test
  void refusesARequestWithoutAVmSegment() {
    assertNotFound(request("/internal/vms/" + VM, null));
  }

  @Test
  void refusesARequestWhoseVmSegmentIsNotAHarness() {
    assertNotFound(request("/internal/vms/" + VM + "/other/api", null));
  }

  private static MockHttpServletRequest request(String uri, String query) {
    var request = new MockHttpServletRequest();
    request.setContextPath("");
    request.setRequestURI(uri);
    if (query != null) {
      request.setQueryString(query);
    }
    return request;
  }

  private static void assertNotFound(MockHttpServletRequest request) {
    assertThatThrownBy(() -> HarnessTarget.of(request))
        .isInstanceOfSatisfying(
            TunnelException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));
    assertThatThrownBy(() -> HarnessTarget.of(request, VM.toString()))
        .isInstanceOfSatisfying(
            TunnelException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }
}
