package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import io.github.kalpak44.workspacedai.vmmanager.vault.VaultException;
import io.github.kalpak44.workspacedai.vmmanager.vm.SecretNotFoundException;
import io.github.kalpak44.workspacedai.vmmanager.vm.SecretRefusedException;
import io.github.kalpak44.workspacedai.vmmanager.vm.ServerTypeNotOfferedException;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmInventoryNotLoadedException;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmNotFoundException;
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

  @ExceptionHandler(VmNotFoundException.class)
  ProblemDetail notFound(VmNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(SecretNotFoundException.class)
  ProblemDetail secretNotFound(SecretNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(SecretRefusedException.class)
  ProblemDetail secretRefused(SecretRefusedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(ServerTypeNotOfferedException.class)
  ProblemDetail notOffered(ServerTypeNotOfferedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(VmInventoryNotLoadedException.class)
  ProblemDetail notLoaded(VmInventoryNotLoadedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
  }

  // A missing token is this deployment's fault; anything else is Hetzner's.
  @ExceptionHandler(HetznerException.class)
  ProblemDetail hetznerFailed(HetznerException e) {
    log.warn("Hetzner call failed: {}", e.getMessage());
    var status =
        e instanceof HetznerException.NotConfigured
            ? HttpStatus.SERVICE_UNAVAILABLE
            : HttpStatus.BAD_GATEWAY;
    return ProblemDetail.forStatusAndDetail(status, e.getMessage());
  }

  // As for Hetzner: an address left unset is this deployment's fault, anything else is Vault's.
  @ExceptionHandler(VaultException.class)
  ProblemDetail vaultFailed(VaultException e) {
    log.warn("Vault call failed: {}", e.getMessage());
    var status =
        e instanceof VaultException.NotConfigured
            ? HttpStatus.SERVICE_UNAVAILABLE
            : HttpStatus.BAD_GATEWAY;
    return ProblemDetail.forStatusAndDetail(status, e.getMessage());
  }
}
