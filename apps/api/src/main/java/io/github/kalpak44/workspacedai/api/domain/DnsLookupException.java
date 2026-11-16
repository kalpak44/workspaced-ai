package io.github.kalpak44.workspacedai.api.domain;

/** The resolver did not answer, as opposed to answering that a name has no such record. */
public class DnsLookupException extends RuntimeException {

  public DnsLookupException(String message, Throwable cause) {
    super(message, cause);
  }
}
