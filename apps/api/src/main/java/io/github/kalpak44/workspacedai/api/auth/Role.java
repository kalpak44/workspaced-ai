package io.github.kalpak44.workspacedai.api.auth;

/** What a user may do beyond their own workspaces. */
public enum Role {
  USER,
  /** Also lists, creates and deletes users. */
  ADMIN;

  /** The authority Spring Security's hasRole checks for. */
  String authority() {
    return "ROLE_" + name();
  }
}
