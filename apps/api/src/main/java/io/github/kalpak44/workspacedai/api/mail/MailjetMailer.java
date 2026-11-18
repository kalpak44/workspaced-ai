package io.github.kalpak44.workspacedai.api.mail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sends through Mailjet's Send API v3.1, which takes and answers JSON. */
@Component
public class MailjetMailer implements Mailer {

  private final MailProperties properties;
  private final JsonMapper json;
  private final HttpClient http;

  public MailjetMailer(MailProperties properties, JsonMapper json) {
    this.properties = properties;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
  }

  @Override
  public String send(Mail mail) {
    if (!properties.isConfigured()) {
      throw new MailUnavailableException("no Mailjet account is configured", null);
    }
    var message = new LinkedHashMap<String, Object>();
    message.put("From", address(mail.from()));
    message.put("To", mail.to().stream().map(MailjetMailer::address).toList());
    if (mail.replyTo() != null) {
      message.put("ReplyTo", address(mail.replyTo()));
    }
    message.put("Subject", mail.subject());
    message.put("TextPart", mail.text());
    if (mail.html() != null) {
      message.put("HTMLPart", mail.html());
    }
    if (!mail.images().isEmpty()) {
      message.put(
          "InlinedAttachments",
          mail.images().stream()
              .map(
                  image ->
                      Map.of(
                          "ContentType", image.contentType(),
                          "Filename", image.filename(),
                          "ContentID", image.id(),
                          "Base64Content", Base64.getEncoder().encodeToString(image.content())))
              .toList());
    }
    var credentials =
        Base64.getEncoder()
            .encodeToString(
                (properties.apiKey() + ":" + properties.secretKey())
                    .getBytes(StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(
                URI.create(withoutTrailingSlashes(properties.url().toString()) + "/v3.1/send"))
            .header("Authorization", "Basic " + credentials)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(properties.timeout())
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("Messages", List.of(message)))))
            .build();
    var response = send(request);
    var sent = read(response).path("Messages").path(0);
    if (response.statusCode() != 200 || !"success".equals(sent.path("Status").asString())) {
      throw new MailFailedException("answered " + response.statusCode() + ": " + errors(response));
    }
    return sent.path("To").path(0).path("MessageID").asString("");
  }

  private static String withoutTrailingSlashes(String url) {
    var end = url.length();
    while (end > 0 && url.charAt(end - 1) == '/') {
      end--;
    }
    return url.substring(0, end);
  }

  private static Map<String, String> address(Address address) {
    var fields = new LinkedHashMap<String, String>();
    fields.put("Email", address.email());
    if (address.name() != null && !address.name().isBlank()) {
      fields.put("Name", address.name());
    }
    return fields;
  }

  private HttpResponse<String> send(HttpRequest request) {
    HttpResponse<String> response;
    try {
      response = http.send(request, BodyHandlers.ofString());
    } catch (IOException e) {
      throw new MailUnavailableException("Mailjet cannot be reached", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MailUnavailableException("interrupted while calling Mailjet", e);
    }
    var status = response.statusCode();
    if (status == 429 || status >= 500) {
      throw new MailUnavailableException("Mailjet answered " + status, null);
    }
    return response;
  }

  private JsonNode read(HttpResponse<String> response) {
    try {
      return json.readTree(response.body());
    } catch (JacksonException e) {
      throw new MailFailedException("answered " + response.statusCode() + " with no JSON");
    }
  }

  /**
   * Mailjet's own explanation: per message for a refused message, at the top level for a refused
   * key.
   */
  private String errors(HttpResponse<String> response) {
    var body = read(response);
    var messages = new ArrayList<String>();
    for (var error : body.path("Messages").path(0).path("Errors")) {
      messages.add(error.path("ErrorMessage").asString(""));
    }
    if (messages.isEmpty() && body.path("ErrorMessage").isString()) {
      messages.add(body.path("ErrorMessage").asString());
    }
    return messages.isEmpty() ? "no reason given" : String.join("; ", messages);
  }
}
