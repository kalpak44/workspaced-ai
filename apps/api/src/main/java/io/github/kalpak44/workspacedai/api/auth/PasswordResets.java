package io.github.kalpak44.workspacedai.api.auth;

/** Mails a user a link to choose a new password; the password itself is never seen here. */
public interface PasswordResets {

  void send(String email);
}
