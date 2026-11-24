package io.github.kalpak44.workspacedai.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainConnectSignerTest {

  static final KeyPair KEYS = keys();
  static final DnsProvider PROVIDER = new DnsProvider("GoDaddy", "https://dcc.example.net/");

  static KeyPair keys() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static DomainConnectSigner signer(String key) {
    return new DomainConnectSigner(
        new DomainProperties(
            key,
            "_dck1",
            "workspaced.online",
            "https://app",
            "dns://x",
            Duration.ofSeconds(1),
            20,
            List.of(),
            List.of()));
  }

  static String pem() {
    return "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(KEYS.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
  }

  @Test
  void signsTheQueryAProviderVerifiesWithThePublishedKey() throws Exception {
    var variables = new LinkedHashMap<String, String>();
    variables.put("ipv4", "203.0.113.10");
    variables.put("ipv6", "2001:db8::1");
    variables.put("vmid", "8d1e4b0a");

    var url =
        signer(pem())
            .applyUrl(
                PROVIDER,
                "workspace-apex",
                "example.com",
                "@",
                variables,
                "https://app/workspaces/w/settings/domains",
                "s-1");

    assertThat(url)
        .startsWith(
            "https://dcc.example.net/v2/domainTemplates/providers/workspaced.online/services/"
                + "workspace-apex/apply?domain=example.com&ipv4=203.0.113.10&ipv6=2001%3Adb8%3A%3A1"
                + "&vmid=8d1e4b0a&redirect_uri=https%3A%2F%2Fapp%2Fworkspaces%2Fw%2Fsettings"
                + "%2Fdomains&state=s-1&sig=")
        .endsWith("&key=_dck1")
        .doesNotContain("host=");
    var query = url.substring(url.indexOf('?') + 1);
    var signed = query.substring(0, query.indexOf("&sig="));
    var sig = URLDecoder.decode(query.replaceAll(".*&sig=([^&]*).*", "$1"), StandardCharsets.UTF_8);
    var verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(KEYS.getPublic());
    verifier.update(signed.getBytes(StandardCharsets.UTF_8));
    assertThat(verifier.verify(Base64.getDecoder().decode(sig))).isTrue();
  }

  @Test
  void namesTheHostForANameUnderTheDomain() {
    var url =
        signer(pem())
            .applyUrl(PROVIDER, "workspace", "example.com", "app", new LinkedHashMap<>(), "r", "s");

    assertThat(url).contains("apply?domain=example.com&host=app&redirect_uri=r&state=s&sig=");
  }

  @Test
  void signsNothingWithoutAKeyAndRefusesOneItCannotRead() {
    var unsigned = signer("");
    assertThat(unsigned.isConfigured()).isFalse();
    assertThatThrownBy(
            () ->
                unsigned.applyUrl(
                    PROVIDER, "workspace", "example.com", "app", new LinkedHashMap<>(), "r", "s"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(signer(pem()).isConfigured()).isTrue();
    assertThatThrownBy(
            () -> signer("-----BEGIN PRIVATE KEY-----\nbm9wZQ==\n-----END PRIVATE KEY-----"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PKCS#8");
  }
}
