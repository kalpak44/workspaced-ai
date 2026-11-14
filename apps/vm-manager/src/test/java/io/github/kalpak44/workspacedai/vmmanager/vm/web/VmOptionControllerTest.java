package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.FakeHetzner;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmOptionDto;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class VmOptionControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  private static final FakeHetzner HETZNER = new FakeHetzner();

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  // cx22 is deprecated, cx43 is not sold in nbg1, and cx53 does not exist at all.
  @DynamicPropertySource
  static void hetzner(DynamicPropertyRegistry registry) {
    registry.add("workspaced.hetzner.token", () -> "t0ken");
    registry.add("workspaced.hetzner.base-url", HETZNER::url);
    registry.add("workspaced.hetzner.location", () -> "nbg1");
    registry.add("workspaced.vm.server-types", () -> "cx23,cx33,cx22,cx43,cx53");
  }

  @AfterAll
  static void stop() {
    HETZNER.close();
  }

  @Test
  void offersOnlyWhatHetznerSellsInTheLocationDefaultFirst() throws Exception {
    var response = get();
    assertThat(response.statusCode()).isEqualTo(200);
    var options = JSON.readValue(response.body(), new TypeReference<List<VmOptionDto>>() {});

    assertThat(options).extracting(VmOptionDto::serverType).containsExactly("cx23", "cx33");
    assertThat(options).extracting(VmOptionDto::isDefault).containsExactly(true, false);
    var cx33 = options.get(1);
    assertThat(cx33.cores()).isEqualTo(4);
    assertThat(cx33.memoryGb()).isEqualTo(8.0);
    assertThat(cx33.diskGb()).isEqualTo(80);
    assertThat(cx33.location()).isEqualTo("nbg1");
    assertThat(cx33.monthlyNet()).isEqualTo(new BigDecimal("8.49"));
    assertThat(cx33.monthlyGross()).isEqualTo(new BigDecimal("10.19"));
  }

  @Test
  void asksHetznerOnceForRepeatedRequests() throws Exception {
    get();
    var after = HETZNER.requests.get();
    get();
    get();
    assertThat(HETZNER.requests).hasValue(after);
  }

  private java.net.http.HttpResponse<String> get() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/vm-options"))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
