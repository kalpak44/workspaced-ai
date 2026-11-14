package io.github.kalpak44.workspacedai.agenthub.agent;

/**
 * The token is unknown, expired, already used, or issued for another VM. One message for all, so a
 * caller learns nothing about which.
 */
public class EnrollmentRefusedException extends RuntimeException {

  public EnrollmentRefusedException() {
    super("enrollment token refused");
  }
}
