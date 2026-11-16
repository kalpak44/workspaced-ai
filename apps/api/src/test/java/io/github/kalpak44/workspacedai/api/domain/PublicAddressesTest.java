package io.github.kalpak44.workspacedai.api.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class PublicAddressesTest {

  @Test
  void letsThroughHttpsToAPublicAddress() {
    assertThatCode(() -> PublicAddresses.require(URI.create("https://1.1.1.1/v2/settings")))
        .doesNotThrowAnyException();
    assertThatCode(() -> PublicAddresses.require(URI.create("https://[2606:4700:4700::1111]/")))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesAnythingThatCouldReachInsideTheCluster() {
    for (var url :
        new String[] {
          "http://1.1.1.1/",
          "https://127.0.0.1/",
          "https://10.43.0.10/",
          "https://192.168.1.13/",
          "https://172.16.0.1/",
          "https://100.64.0.1/",
          "https://169.254.169.254/latest/meta-data",
          "https://0.0.0.0/",
          "https://[::1]/",
          "https://[fd00::1]/",
          "https://[fe80::1]/",
          "https://user@1.1.1.1/",
          "https://does-not-resolve.invalid/"
        }) {
      assertThatThrownBy(() -> PublicAddresses.require(URI.create(url)))
          .as(url)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
