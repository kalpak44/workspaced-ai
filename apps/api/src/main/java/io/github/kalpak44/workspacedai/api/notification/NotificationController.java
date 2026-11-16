package io.github.kalpak44.workspacedai.api.notification;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link NotificationApi}. */
@RestController
public class NotificationController implements NotificationApi {

  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<Notification>> list() {
    return ResponseEntity.ok(service.list(CurrentUser.id()));
  }

  @Override
  public ResponseEntity<List<Notification>> setRead(ReadRequest request) {
    return ResponseEntity.ok(service.setRead(CurrentUser.id(), request.ids(), request.read()));
  }
}
