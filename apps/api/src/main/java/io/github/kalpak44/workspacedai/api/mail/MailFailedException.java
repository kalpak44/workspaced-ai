package io.github.kalpak44.workspacedai.api.mail;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A 502: Mailjet refused or failed the message. */
public class MailFailedException extends ResponseStatusException {

  public MailFailedException(String reason) {
    super(HttpStatus.BAD_GATEWAY, "Mailjet failed: " + reason);
  }
}
