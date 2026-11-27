package io.github.kalpak44.workspacedai.api.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
          null,
          "cx23",
          true,
          2,
          4.0,
          40,
          "shared",
          "x86",
          "nbg1",
          new BigDecimal("5.49"),
          new BigDecimal("6.59"),
          true,
          false,
          true);

  @LocalServerPort int port;
  @MockitoBean VmManager vmManager;
  @Autowired UserRepository users;

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
  void listsFlexAlongsideAvailableNormalPlansSoUsersCanSwitchTariffs() throws Exception {
    var normal =
        new VmSize(
            null,
            "cx23",
            true,
            2,
            4.0,
            40,
            "shared",
            "x86",
            "nbg1",
            new BigDecimal("3.00"),
            new BigDecimal("4.00"),
            true,
            false,
            true);
    var flex =
        new VmSize(
            null,
            "cpx22",
            false,
            2,
            4.0,
            80,
            "shared",
            "x86",
            "nbg1",
            new BigDecimal("12.00"),
            new BigDecimal("15.00"),
            false,
            false,
            true);
    when(vmManager.sizes()).thenReturn(List.of(normal, flex));

    var response = list();
    assertThat(response.statusCode()).isEqualTo(200);
    var sizes = JSON.readTree(response.body());
    assertThat(sizes.size()).isEqualTo(2);
    assertThat(sizes.get(0).get("plan").asString()).isEqualTo("Starter");
    assertThat(sizes.get(0).get("available").asBoolean()).isTrue();
    assertThat(sizes.get(0).get("isDefault").asBoolean()).isTrue();
    assertThat(sizes.get(1).get("plan").asString()).isEqualTo("Starter Flex");
    assertThat(sizes.get(1).get("available").asBoolean()).isTrue();
    assertThat(sizes.get(1).get("isDefault").asBoolean()).isFalse();
  }

  @Test
  void showsAnAdminTheCostAndEveryoneElseThePlanPrice() throws Exception {
    when(vmManager.sizes()).thenReturn(List.of(CX23));
    var name = "vera-" + UUID.randomUUID();
    users.save(TestUsers.newUser(name));

    var admin = JSON.readTree(list(TestUsers.bearer(port)).body()).get(0);
    assertThat(admin.get("monthlyNet").decimalValue()).isEqualByComparingTo("5.49");
    assertThat(admin.get("monthlyGross").decimalValue()).isEqualByComparingTo("6.59");

    var user =
        JSON.readTree(
                list(TestUsers.bearer(
                        users.findByIdpSubject(TestUsers.subject(name)).orElseThrow()))
                    .body())
            .get(0);
    assertThat(user.get("monthlyGross").decimalValue()).isEqualByComparingTo("10.645833");
    assertThat(user.get("plan").asString()).isEqualTo("Starter");
    assertThat(user.get("serverType").asString()).isEqualTo("cx23");
  }

  @Test
  void answersUnavailableWhileTheVmManagerIsDown() throws Exception {
    when(vmManager.sizes()).thenThrow(new VmManagerUnavailableException("down", null));
    assertThat(list().statusCode()).isEqualTo(503);
  }

  private HttpResponse<String> list() throws Exception {
    return list(TestUsers.bearer(port));
  }

  private HttpResponse<String> list(String authorization) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/vm-sizes"))
            .header("Authorization", authorization)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
