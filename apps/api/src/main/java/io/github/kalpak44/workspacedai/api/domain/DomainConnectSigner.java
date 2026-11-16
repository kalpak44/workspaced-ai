package io.github.kalpak44.workspacedai.api.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the link that has a DNS provider apply one of our templates. It is signed, so the provider
 * checks it against the key published under our provider id and no one else can send a user there
 * with values of their own.
 */
@Component
public class DomainConnectSigner {

  private final DomainProperties properties;
  private final PrivateKey key;

  public DomainConnectSigner(DomainProperties properties) {
    this.properties = properties;
    this.key = parse(properties.privateKey());
  }

  public boolean isConfigured() {
    return key != null;
  }

  /**
   * @param host the name under the zone, or {@code @} for the zone itself
   * @param variables the template's variables, in the order they are sent
   */
  public String applyUrl(
      DnsProvider provider,
      String serviceId,
      String zone,
      String host,
      Map<String, String> variables,
      String redirectUri,
      String state) {
    if (key == null) {
      throw new IllegalStateException("no Domain Connect signing key is configured");
    }
    var query = new StringBuilder("domain=").append(encode(zone));
    if (!"@".equals(host)) {
      query.append("&host=").append(encode(host));
    }
    query
        .append(
            variables.entrySet().stream()
                .map(e -> "&" + encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining()))
        .append("&redirect_uri=")
        .append(encode(redirectUri))
        .append("&state=")
        .append(encode(state));
    // The signature covers the query as sent, every value already encoded, sig and key aside.
    return provider.syncUrl().replaceAll("/+$", "")
        + "/v2/domainTemplates/providers/"
        + properties.providerId()
        + "/services/"
        + serviceId
        + "/apply?"
        + query
        + "&sig="
        + encode(sign(query.toString()))
        + "&key="
        + encode(properties.keyName());
  }

  private String sign(String text) {
    try {
      var signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(key);
      signature.update(text.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(signature.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not sign a Domain Connect request", e);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  // A key that is set but unreadable fails the start, rather than quietly leaving every user
  // to add records by hand.
  private static PrivateKey parse(String pem) {
    if (pem == null || pem.isBlank()) {
      return null;
    }
    var body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    try {
      return KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("the Domain Connect signing key is not a PKCS#8 RSA key", e);
    }
  }
}
