package io.github.kalpak44.workspacedai.api.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routes, validation and documentation are declared on {@link DomainApi}. */
@RestController
public class DomainController implements DomainApi {

  private final DomainService service;

  public DomainController(DomainService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<WorkspaceDomain>> list(UUID workspaceId) {
    return ResponseEntity.ok(service.list(workspaceId));
  }

  @Override
  public ResponseEntity<WorkspaceDomain> attach(UUID workspaceId, AttachDomainRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.attach(workspaceId, request.name()));
  }

  @Override
  public ResponseEntity<List<DnsRecordView>> detach(UUID workspaceId, UUID domainId) {
    return ResponseEntity.ok(service.detach(workspaceId, domainId));
  }
}
