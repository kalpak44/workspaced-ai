package io.github.kalpak44.workspacedai.agenthub.agent.web;

import io.github.kalpak44.workspacedai.agenthub.agent.AlreadyEnrolledException;
import io.github.kalpak44.workspacedai.agenthub.agent.EnrollmentRefusedException;
import io.github.kalpak44.workspacedai.agenthub.agent.MalformedKeyException;
import io.github.kalpak44.workspacedai.agenthub.agent.SignatureRefusedException;
import io.github.kalpak44.workspacedai.agenthub.tunnel.TunnelException;
import io.github.kalpak44.workspacedai.agenthub.vmmanager.VmManagerException;
import io.github.kalpak44.workspacedai.agenthub.vmmanager.VmNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the domain's exceptions into problem details, so the services never speak HTTP and the
 * controllers never catch.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(MalformedKeyException.class)
  ProblemDetail malformedKey(MalformedKeyException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(EnrollmentRefusedException.class)
  ProblemDetail enrollmentRefused(EnrollmentRefusedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  // The reason is logged but not answered, so a forger cannot tell which check it failed.
  @ExceptionHandler(SignatureRefusedException.class)
  ProblemDetail signatureRefused(SignatureRefusedException e) {
    log.info("Refused a signed request: {}", e.getMessage());
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "request signature refused");
  }

  @ExceptionHandler(AlreadyEnrolledException.class)
  ProblemDetail alreadyEnrolled(AlreadyEnrolledException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(VmNotFoundException.class)
  ProblemDetail vmNotFound(VmNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  // It lacks its harness image or Vault: its deployment's to fix, which a later retry may find.
  @ExceptionHandler(VmManagerException.Unavailable.class)
  ProblemDetail vmManagerUnavailable(VmManagerException.Unavailable e) {
    log.warn("VM manager unavailable: {}", e.getMessage());
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
  }

  // No worker came free, the harness refused, or the worker dropped before an answer.
  @ExceptionHandler(TunnelException.class)
  ProblemDetail tunnel(TunnelException e) {
    return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
  }

  @ExceptionHandler(VmManagerException.class)
  ProblemDetail vmManagerFailed(VmManagerException e) {
    log.warn("VM manager call failed: {}", e.getMessage());
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
  }
}
