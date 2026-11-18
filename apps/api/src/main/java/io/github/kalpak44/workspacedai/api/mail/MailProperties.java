package io.github.kalpak44.workspacedai.api.mail;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param domain the one domain mail is sent from; Mailjet accepts any address on it
 * @param noReply the address before the @ the platform's own notifications come from; nothing
 *     routes mail to it, so a reply bounces rather than reaching nobody
 * @param noReplyName shown beside that address
 * @param apiKey Mailjet's public API key; unset, as it is locally, nothing is sent and sending
 *     answers 503
 * @param secretKey Mailjet's private API key
 * @param url the API root, replaced only by tests
 * @param timeout how long one call may take
 */
@ConfigurationProperties("workspaced.mail")
public record MailProperties(
    @DefaultValue("workspaced.online") String domain,
    @DefaultValue("noreply") String noReply,
    @DefaultValue("workspaced") String noReplyName,
    String apiKey,
    String secretKey,
    @DefaultValue("https://api.mailjet.com") URI url,
    @DefaultValue("20s") Duration timeout) {

  boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank() && secretKey != null && !secretKey.isBlank();
  }
}
