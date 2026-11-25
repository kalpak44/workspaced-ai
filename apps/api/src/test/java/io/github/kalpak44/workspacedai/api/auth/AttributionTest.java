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

  @Test
  void namesAReferralLinkAsItsSourceUnlessTheLinkNamesOne() {
    var referral = Attribution.of(Map.of("ref", "K7m2xq9p"));
    assertThat(referral.referral()).isEqualTo("k7m2xq9p");
    assertThat(referral.source()).isEqualTo("referral");

    var tagged = Attribution.of(Map.of("ref", "k7m2xq9p", "utm_source", "newsletter"));
    assertThat(tagged.source()).isEqualTo("newsletter");
    assertThat(tagged.referral()).isEqualTo("k7m2xq9p");
  }
}
