package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/** Routes and documentation are declared on {@link VmOptionApi}. */
@RestController
public class VmOptionController implements VmOptionApi {

  private static final Logger log = LoggerFactory.getLogger(VmOptionController.class);

  private final VmOptions options;

  public VmOptionController(VmOptions options) {
    this.options = options;
  }

  @Override
  public ResponseEntity<List<VmOption>> list() {
    return ResponseEntity.ok(options.list());
  }

  // A missing token is this deployment's fault; anything else is Hetzner's.
  @ExceptionHandler(HetznerException.class)
  ProblemDetail hetznerFailed(HetznerException e) {
    log.warn("listing VM options failed: {}", e.getMessage());
    var status =
        e instanceof HetznerException.NotConfigured
            ? HttpStatus.SERVICE_UNAVAILABLE
            : HttpStatus.BAD_GATEWAY;
    return ProblemDetail.forStatusAndDetail(status, e.getMessage());
  }
}
