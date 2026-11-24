package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AttributionTest {

  @Test
  void cutsWhatALinkSaysDownToAShortToken() {
    var attribution =
        Attribution.of(
            Map.of("utm_source", "  News.YCombinator.com ", "utm_campaign", "Show HN <launch>!"));
    assertThat(attribution).isEqualTo(new Attribution("news.ycombinator.com", "show-hn-launch"));
  }

  @Test
  void keepsNothingForAnEmptyOrMissingValue() {
    assertThat(Attribution.of(Map.of("utm_source", "!!!", "other", "x")).isEmpty()).isTrue();
    assertThat(Attribution.of(Map.of("utm_source", "x".repeat(300))).source()).hasSize(100);
  }
}
