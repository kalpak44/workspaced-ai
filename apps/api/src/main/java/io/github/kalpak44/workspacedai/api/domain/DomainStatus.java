package io.github.kalpak44.workspacedai.api.domain;

public enum DomainStatus {
  /** Its DNS does not point at the workspace yet, or no longer does. */
  PENDING,
  /** Public DNS resolves it to the workspace's VM. */
  ACTIVE
}
