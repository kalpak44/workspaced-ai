package io.github.kalpak44.workspacedai.api.mail;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 503: no mail can be sent right now, though nothing is wrong with the message. */
public class MailUnavailableException extends ResponseStatusException {

  public MailUnavailableException(String reason, Throwable cause) {
    super(HttpStatus.SERVICE_UNAVAILABLE, "mail is unavailable: " + reason, cause);
  }
}
