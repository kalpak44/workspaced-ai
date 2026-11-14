package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HetznerClientTest {

  final FakeHetzner hetzner = new FakeHetzner();

  @AfterEach
  void tearDown() {
    hetzner.close();
  }

  @Test
  void readsEveryPageWithTheToken() {
    var types = client("t0ken").serverTypes();
    assertThat(types).extracting(ServerType::name).containsExactly("cx23", "cx22", "cx33", "cx43");
    assertThat(hetzner.authorizations).containsOnly("Bearer t0ken").hasSize(2);
  }

  @Test
  void keepsWhatAVmIsSizedAndPricedBy() {
    var cx23 = client("t0ken").serverTypes().getFirst();
    assertThat(cx23.cores()).isEqualTo(2);
    assertThat(cx23.memoryGb()).isEqualTo(4.0);
    assertThat(cx23.diskGb()).isEqualTo(40);
    assertThat(cx23.cpuType()).isEqualTo("shared");
    assertThat(cx23.architecture()).isEqualTo("x86");
    assertThat(cx23.deprecated()).isFalse();
    assertThat(cx23.priceIn("nbg1"))
        .hasValueSatisfying(
            price -> assertThat(price.monthlyNet()).isEqualTo(new BigDecimal("5.49")));
    assertThat(cx23.priceIn("nbg1"))
        .hasValueSatisfying(
            price -> assertThat(price.monthlyGross()).isEqualTo(new BigDecimal("6.59")));
    assertThat(cx23.priceIn("ash")).isEmpty();
  }

  @Test
  void marksADeprecatedType() {
    assertThat(client("t0ken").serverTypes().get(1).deprecated()).isTrue();
  }

  @Test
  void neverCallsHetznerWithoutAToken() {
    assertThatThrownBy(() -> client(" ").serverTypes())
        .isInstanceOf(HetznerException.NotConfigured.class);
    assertThatThrownBy(() -> client(null).serverTypes())
        .isInstanceOf(HetznerException.NotConfigured.class);
    assertThat(hetzner.requests).hasValue(0);
  }

  @Test
  void reportsAFailedAnswer() {
    hetzner.status = 401;
    assertThatThrownBy(() -> client("bad").serverTypes())
        .isInstanceOf(HetznerException.class)
        .isNotInstanceOf(HetznerException.NotConfigured.class)
        .hasMessageContaining("401");
  }

  private HetznerClient client(String token) {
    return new HetznerClient(new HetznerProperties(token, hetzner.url(), "nbg1"));
  }
}
