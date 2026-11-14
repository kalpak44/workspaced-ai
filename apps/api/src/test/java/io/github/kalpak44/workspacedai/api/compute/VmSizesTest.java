package io.github.kalpak44.workspacedai.api.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class VmSizesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final VmSize CX23 =
      new VmSize(
          "cx23",
          true,
          2,
          4.0,
          40,
          "shared",
          "x86",
          "nbg1",
          new BigDecimal("5.49"),
          new BigDecimal("6.59"));

  @LocalServerPort int port;
  @MockitoBean VmManager vmManager;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void listsTheVmManagersSizes() throws Exception {
    when(vmManager.sizes()).thenReturn(List.of(CX23));

    var response = list();

    assertThat(response.statusCode()).isEqualTo(200);
    var size = JSON.readTree(response.body()).get(0);
    assertThat(size.get("serverType").asString()).isEqualTo("cx23");
    assertThat(size.get("cores").asInt()).isEqualTo(2);
    assertThat(size.get("isDefault").asBoolean()).isTrue();
  }

  @Test
  void answersUnavailableWhileTheVmManagerIsDown() throws Exception {
    when(vmManager.sizes()).thenThrow(new VmManagerUnavailableException("down", null));
    assertThat(list().statusCode()).isEqualTo(503);
  }

  private HttpResponse<String> list() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/vm-sizes"))
            .header("Authorization", TestUsers.bearer(port))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
