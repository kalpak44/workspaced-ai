package io.github.kalpak44.workspacedai.api.billing;

/** Where a top-up's payment stands. Only PAID has credited the balance. */
public enum TopUpStatus {
  PENDING,
  PAID,
  FAILED,
  // Abandoned at checkout; kept for the record but not listed.
  EXPIRED
}
