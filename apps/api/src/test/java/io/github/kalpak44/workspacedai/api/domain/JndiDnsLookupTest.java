package io.github.kalpak44.workspacedai.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JndiDnsLookupTest {

  @Test
  void joinsTheStringsOfATxtRecord() {
    assertThat(JndiDnsLookup.unquote("\"api.godaddy.com\"")).isEqualTo("api.godaddy.com");
    assertThat(JndiDnsLookup.unquote("\"p=1,a=RS256,\" \"d=MIIB\""))
        .isEqualTo("p=1,a=RS256,d=MIIB");
    assertThat(JndiDnsLookup.unquote("plain")).isEqualTo("plain");
  }
}
