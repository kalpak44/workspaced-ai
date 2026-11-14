package io.github.kalpak44.workspacedai.agenthub.agent.web;

import io.github.kalpak44.workspacedai.agenthub.agent.EnrollmentService;
import io.github.kalpak44.workspacedai.agenthub.agent.web.dto.IssueTokenDto;
import io.github.kalpak44.workspacedai.agenthub.agent.web.dto.IssuedTokenDto;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes the VM manager calls from inside the cluster; {@link InternalRoutesFilter} refuses
 * them to anything that came through the ingress.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

  private final EnrollmentService enrollments;

  public InternalController(EnrollmentService enrollments) {
    this.enrollments = enrollments;
  }

  @PostMapping("/enrollments")
  public ResponseEntity<IssuedTokenDto> issue(@Valid @RequestBody IssueTokenDto body) {
    var issued = enrollments.issue(body.vmId());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new IssuedTokenDto(issued.vmId(), issued.token(), issued.expiresAt()));
  }

  @DeleteMapping("/agents/{vmId}")
  public ResponseEntity<Void> revoke(@PathVariable UUID vmId) {
    enrollments.revoke(vmId);
    return ResponseEntity.noContent().build();
  }
}
