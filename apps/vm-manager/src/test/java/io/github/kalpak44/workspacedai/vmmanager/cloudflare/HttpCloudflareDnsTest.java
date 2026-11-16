package io.github.kalpak44.workspacedai.vmmanager.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpCloudflareDnsTest {

  final FakeCloudflare cloudflare = new FakeCloudflare();

  @AfterEach
  void tearDown() {
    cloudflare.close();
  }

  @Test
  void listsEveryPageOfTheSuffixWithTheToken() {
    cloudflare.perPage = 2;
    cloudflare.add("A", "a.vm.example.com", "203.0.113.1");
    cloudflare.add("AAAA", "a.vm.example.com", "2001:db8::1");
    cloudflare.add("A", "b.vm.example.com", "203.0.113.2");
    cloudflare.add("CNAME", "ai.example.com", "tunnel.example.net");

    var records = dns("t0ken", "z1").records(".vm.example.com");

    assertThat(records)
        .extracting(DnsRecord::name)
        .containsExactlyInAnyOrder("a.vm.example.com", "a.vm.example.com", "b.vm.example.com");
    assertThat(records)
        .filteredOn(r -> r.type().equals("AAAA"))
        .singleElement()
        .satisfies(r -> assertThat(r.content()).isEqualTo("2001:db8::1"));
    assertThat(cloudflare.queries).hasSize(2).allMatch(q -> q.contains("name.endswith=.vm."));
    assertThat(cloudflare.authorizations).containsOnly("Bearer t0ken");
  }

  @Test
  void createsARecordThatIsNotProxied() {
    dns("t0ken", "z1").create("A", "a.vm.example.com", "203.0.113.1", 60);

    var record = cloudflare.records.values().iterator().next();
    assertThat(record.path("type").asString()).isEqualTo("A");
    assertThat(record.path("name").asString()).isEqualTo("a.vm.example.com");
    assertThat(record.path("content").asString()).isEqualTo("203.0.113.1");
    assertThat(record.path("ttl").asInt()).isEqualTo(60);
    assertThat(record.path("proxied").asBoolean(true)).isFalse();
  }

  @Test
  void pointsARecordAtANewAddress() {
    var id = cloudflare.add("A", "a.vm.example.com", "203.0.113.1");

    dns("t0ken", "z1").update(id, "203.0.113.9");

    assertThat(cloudflare.records.get(id).path("content").asString()).isEqualTo("203.0.113.9");
  }

  @Test
  void deletesARecordAndTreatsOneAlreadyGoneAsDeleted() {
    var id = cloudflare.add("A", "a.vm.example.com", "203.0.113.1");
    var dns = dns("t0ken", "z1");

    dns.delete(id);
    dns.delete(id);

    assertThat(cloudflare.records).isEmpty();
  }

  @Test
  void reportsWhatCloudflareRefused() {
    cloudflare.status = 403;

    assertThatThrownBy(() -> dns("t0ken", "z1").create("A", "a", "203.0.113.1", 60))
        .isInstanceOf(CloudflareException.class)
        .hasMessage("Cloudflare answered 403 on POST dns_records: Authentication error");
  }

  @Test
  void treatsAnUnknownRecordAsAFailureOutsideADelete() {
    assertThatThrownBy(() -> dns("t0ken", "z1").update("missing", "203.0.113.1"))
        .isInstanceOf(CloudflareException.class)
        .hasMessageContaining("404");
  }

  @Test
  void asksNothingWithoutATokenAndAZone() {
    assertThat(dns("", "z1").isConfigured()).isFalse();
    assertThat(dns("t0ken", null).isConfigured()).isFalse();
    assertThat(dns("t0ken", "z1").isConfigured()).isTrue();

    assertThatThrownBy(() -> dns(" ", "z1").records(".vm.example.com"))
        .isInstanceOf(CloudflareException.NotConfigured.class);
    assertThat(cloudflare.requests).hasValue(0);
  }

  private HttpCloudflareDns dns(String token, String zone) {
    return new HttpCloudflareDns(
        new CloudflareProperties(token, zone, cloudflare.url(), Duration.ofSeconds(2)),
        JsonMapper.builder().build());
  }
}
