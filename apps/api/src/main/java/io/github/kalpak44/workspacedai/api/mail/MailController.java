package io.github.kalpak44.workspacedai.api.mail;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link MailApi}. */
@RestController
public class MailController implements MailApi {

  private final MailService service;

  public MailController(MailService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<MailSettings> settings() {
    return ResponseEntity.ok(service.settings());
  }

  @Override
  public ResponseEntity<SentMail> send(SendMailRequest request) {
    var id =
        service.send(
            request.from(),
            request.trimmedFromName(),
            request.to(),
            request.replyTo(),
            request.subject(),
            request.text());
    return ResponseEntity.ok(new SentMail(id));
  }
}
