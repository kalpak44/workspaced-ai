package io.github.kalpak44.workspacedai.api.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Balances, their ledger and top-ups, in plain SQL so every balance change is visible here. */
@Repository
public class BillingStore {

  static final long MICROS_PER_CENT = 10_000;
  static final String TOP_UP = "TOP_UP";

  record TopUpRow(
      UUID id,
      UUID userId,
      long amountCents,
      TopUpStatus status,
      String checkoutSessionId,
      String stripeInvoiceId,
      String invoiceNumber,
      Instant createdAt,
      Instant paidAt) {}

  private final JdbcTemplate jdbc;

  public BillingStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  long balanceMicros(UUID user) {
    return jdbc
        .queryForList(
            "SELECT balance_micros FROM billing_account WHERE user_id = ?", Long.class, user)
        .stream()
        .findFirst()
        .orElse(0L);
  }

  Optional<String> customerId(UUID user) {
    return jdbc
        .queryForList(
            "SELECT stripe_customer_id FROM billing_account WHERE user_id = ?", String.class, user)
        .stream()
        .filter(id -> id != null)
        .findFirst();
  }

  /** Keeps the first customer stored, so two racing top-ups still file under one. */
  String claimCustomerId(UUID user, String customerId) {
    return jdbc.queryForObject(
        """
        INSERT INTO billing_account (user_id, stripe_customer_id) VALUES (?, ?)
        ON CONFLICT (user_id) DO UPDATE SET stripe_customer_id =
            COALESCE(billing_account.stripe_customer_id, EXCLUDED.stripe_customer_id)
        RETURNING stripe_customer_id
        """,
        String.class,
        user,
        customerId);
  }

  void createTopUp(UUID id, UUID user, long amountCents) {
    jdbc.update(
        "INSERT INTO billing_top_up (id, user_id, amount_cents, status) VALUES (?, ?, ?, ?)",
        id,
        user,
        amountCents,
        TopUpStatus.PENDING.name());
  }

  void attachSession(UUID id, String sessionId) {
    jdbc.update("UPDATE billing_top_up SET checkout_session_id = ? WHERE id = ?", sessionId, id);
  }

  void deleteTopUp(UUID id) {
    jdbc.update("DELETE FROM billing_top_up WHERE id = ? AND status = 'PENDING'", id);
  }

  Optional<TopUpRow> topUp(UUID id, UUID user) {
    return jdbc
        .query(
            "SELECT * FROM billing_top_up WHERE id = ? AND user_id = ?",
            BillingStore::topUp,
            id,
            user)
        .stream()
        .findFirst();
  }

  Optional<TopUpRow> topUpBySession(String sessionId) {
    return jdbc
        .query(
            "SELECT * FROM billing_top_up WHERE checkout_session_id = ?",
            BillingStore::topUp,
            sessionId)
        .stream()
        .findFirst();
  }

  List<TopUpRow> topUps(UUID user) {
    return jdbc.query(
        "SELECT * FROM billing_top_up WHERE user_id = ? AND status <> 'EXPIRED'"
            + " ORDER BY created_at DESC",
        BillingStore::topUp,
        user);
  }

  /**
   * Marks the top-up paid and credits it once. The ledger's unique reference is what makes this
   * safe to repeat, from the webhook and the returning browser at once say; the balance moves only
   * with the entry that was actually inserted.
   *
   * @return whether this call credited the balance
   */
  @Transactional
  public boolean markPaid(UUID id, String invoiceId, String invoiceNumber) {
    var row =
        jdbc
            .query("SELECT * FROM billing_top_up WHERE id = ? FOR UPDATE", BillingStore::topUp, id)
            .stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no top-up " + id));
    jdbc.update(
        """
        UPDATE billing_top_up SET status = 'PAID', paid_at = COALESCE(paid_at, now()),
            stripe_invoice_id = COALESCE(stripe_invoice_id, ?),
            invoice_number = COALESCE(invoice_number, ?)
        WHERE id = ?
        """,
        invoiceId,
        invoiceNumber,
        id);
    var micros = row.amountCents() * MICROS_PER_CENT;
    var credited =
        jdbc.update(
            """
            INSERT INTO billing_ledger_entry (id, user_id, amount_micros, kind, reference)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT (kind, reference) DO NOTHING
            """,
            UUID.randomUUID(),
            row.userId(),
            micros,
            TOP_UP,
            id.toString());
    if (credited == 0) {
      return false;
    }
    jdbc.update(
        """
        INSERT INTO billing_account (user_id, balance_micros) VALUES (?, ?)
        ON CONFLICT (user_id) DO UPDATE SET
            balance_micros = billing_account.balance_micros + EXCLUDED.balance_micros
        """,
        row.userId(),
        micros);
    return true;
  }

  /** Only a pending top-up can end unpaid; a paid one stays paid whatever arrives late. */
  void markUnpaid(UUID id, TopUpStatus status) {
    jdbc.update(
        "UPDATE billing_top_up SET status = ? WHERE id = ? AND status = 'PENDING'",
        status.name(),
        id);
  }

  private static TopUpRow topUp(ResultSet rs, int rowNum) throws SQLException {
    return new TopUpRow(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getLong("amount_cents"),
        TopUpStatus.valueOf(rs.getString("status")),
        rs.getString("checkout_session_id"),
        rs.getString("stripe_invoice_id"),
        rs.getString("invoice_number"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("paid_at")));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
