package io.github.kalpak44.workspacedai.api.auth;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where a sign-up came from, as the link that started it named it. Nothing in it is trusted: anyone
 * can type a link, so it is cut down to a short token before it is stored or shown.
 *
 * @param source utm_source, such as a site, a newsletter or a referring host; null when none
 * @param campaign utm_campaign; null when none
 * @param referral the referral code of the user who invited them; null when none
 */
public record Attribution(String source, String campaign, String referral) {

  public static final Attribution NONE = new Attribution(null, null, null);

  static final String SOURCE_PARAM = "utm_source";
  static final String CAMPAIGN_PARAM = "utm_campaign";
  static final String REFERRAL_PARAM = "ref";

  /** The source a referral link names when the link carried none of its own. */
  static final String REFERRAL_SOURCE = "referral";

  public Attribution(String source, String campaign) {
    this(source, campaign, null);
  }

  // Carries it from the callback's cookie to the success handler on the same request.
  static final String REQUEST_ATTRIBUTE = Attribution.class.getName();

  private static final int MAX = 100;
  private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9._-]+");

  /** From the parameters of a link, or of the authorization request that kept them. */
  static Attribution of(Map<String, ?> values) {
    var referral = clean(values.get(REFERRAL_PARAM));
    var source = clean(values.get(SOURCE_PARAM));
    return new Attribution(
        source == null && referral != null ? REFERRAL_SOURCE : source,
        clean(values.get(CAMPAIGN_PARAM)),
        referral);
  }

  boolean isEmpty() {
    return source == null && campaign == null && referral == null;
  }

  private static String clean(Object value) {
    if (!(value instanceof String text)) {
      return null;
    }
    var token = UNSAFE.matcher(text.strip().toLowerCase(Locale.ROOT)).replaceAll("-");
    int start = 0;
    int end = token.length();
    while (start < end && token.charAt(start) == '-') {
      start++;
    }
    while (end > start && token.charAt(end - 1) == '-') {
      end--;
    }
    token = token.substring(start, end);
    if (token.isEmpty()) {
      return null;
    }
    return token.length() > MAX ? token.substring(0, MAX) : token;
  }
}
