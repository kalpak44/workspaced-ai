package io.github.kalpak44.workspacedai.agenthub.agent.web;

import io.github.kalpak44.workspacedai.agenthub.agent.AgentService;
import io.github.kalpak44.workspacedai.agenthub.agent.EnrollmentService;
import io.github.kalpak44.workspacedai.agenthub.agent.SignedRequest;
import io.github.kalpak44.workspacedai.agenthub.agent.web.dto.EnrollDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes a VM's supervisor calls, the only ones routed from the internet. Errors are answered
 * by {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

  private final EnrollmentService enrollments;
  private final AgentService agents;

  public AgentController(EnrollmentService enrollments, AgentService agents) {
    this.enrollments = enrollments;
    this.agents = agents;
  }

  @PostMapping("/enroll")
  public ResponseEntity<Void> enroll(@Valid @RequestBody EnrollDto body) {
    enrollments.enroll(body.vmId(), body.token(), body.publicKey());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/ready")
  public ResponseEntity<Void> ready(
      HttpServletRequest request,
      @RequestHeader(name = "X-Agent-Vm", required = false) String vmId,
      @RequestHeader(name = "X-Agent-Timestamp", required = false) String timestamp,
      @RequestHeader(name = "X-Agent-Signature", required = false) String signature,
      @RequestBody(required = false) byte[] body) {
    agents.ready(signed(request, vmId, timestamp, signature, body));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/heartbeat")
  public ResponseEntity<Void> heartbeat(
      HttpServletRequest request,
      @RequestHeader(name = "X-Agent-Vm", required = false) String vmId,
      @RequestHeader(name = "X-Agent-Timestamp", required = false) String timestamp,
      @RequestHeader(name = "X-Agent-Signature", required = false) String signature,
      @RequestBody(required = false) byte[] body) {
    agents.heartbeat(signed(request, vmId, timestamp, signature, body));
    return ResponseEntity.noContent().build();
  }

  // getRequestURI is the path as sent, undecoded and with the context path, which is exactly
  // what the agent signed.
  private static SignedRequest signed(
      HttpServletRequest request, String vmId, String timestamp, String signature, byte[] body) {
    return new SignedRequest(
        vmId,
        timestamp,
        signature,
        request.getMethod(),
        request.getRequestURI(),
        body == null ? new byte[0] : body);
  }
}
