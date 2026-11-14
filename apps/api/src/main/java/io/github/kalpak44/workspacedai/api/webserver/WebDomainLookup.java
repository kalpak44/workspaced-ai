package io.github.kalpak44.workspacedai.api.webserver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The gateway's auth subrequest. Never routed through the public ingress. */
@RestController
public class WebDomainLookup {
  private final WebServerService service;

  public WebDomainLookup(WebServerService service) {
    this.service = service;
  }

  @GetMapping("/internal/web-domains/{name}")
  public ResponseEntity<Void> lookup(@PathVariable String name, HttpServletRequest request) {
    if (request.getHeader("X-Forwarded-For") != null
        || request.getHeader("X-Forwarded-Host") != null
        || request.getHeader("X-Real-IP") != null) {
      return ResponseEntity.notFound().build();
    }
    try {
      return ResponseEntity.noContent()
          .header("X-Workspace-Target", service.resolveDomain(name))
          .build();
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return ResponseEntity.status(403).build();
    }
  }
}
