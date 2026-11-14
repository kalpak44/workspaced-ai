package io.github.kalpak44.workspacedai.agenthub.agent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Keeps both tables in the API's PostgreSQL database, which creates them in its migrations. */
@Repository
public class JdbcAgentStore implements AgentStore {

  private final JdbcClient jdbc;

  public JdbcAgentStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void saveToken(EnrollmentToken token) {
    jdbc.sql(
            """
            insert into enrollment_token (vm_id, token_hash, expires_at) values (?, ?, ?)
            on conflict (vm_id) do update
              set token_hash = excluded.token_hash, expires_at = excluded.expires_at,
                  created_at = now()
            """)
        .params(token.vmId(), token.hash(), Timestamp.from(token.expiresAt()))
        .update();
  }

  @Override
  public Optional<EnrollmentToken> lockToken(UUID vmId) {
    return jdbc.sql(
            "select token_hash, expires_at from enrollment_token where vm_id = ? for update")
        .param(vmId)
        .query(
            (rs, _) ->
                new EnrollmentToken(
                    vmId, rs.getBytes("token_hash"), rs.getTimestamp("expires_at").toInstant()))
        .optional();
  }

  @Override
  public void deleteToken(UUID vmId) {
    jdbc.sql("delete from enrollment_token where vm_id = ?").param(vmId).update();
  }

  @Override
  public boolean isEnrolled(UUID vmId) {
    return jdbc.sql("select count(*) from agent where vm_id = ?")
            .param(vmId)
            .query(Long.class)
            .single()
        > 0;
  }

  @Override
  public boolean saveAgent(UUID vmId, byte[] publicKey, Instant enrolledAt) {
    return jdbc.sql(
                """
                insert into agent (vm_id, public_key, enrolled_at) values (?, ?, ?)
                on conflict (vm_id) do nothing
                """)
            .params(vmId, publicKey, Timestamp.from(enrolledAt))
            .update()
        == 1;
  }

  @Override
  public Optional<byte[]> publicKey(UUID vmId) {
    return jdbc.sql("select public_key from agent where vm_id = ?")
        .param(vmId)
        .query((rs, _) -> rs.getBytes(1))
        .optional();
  }

  @Override
  public void touch(UUID vmId, Instant seenAt) {
    jdbc.sql("update agent set last_seen_at = ? where vm_id = ?")
        .params(Timestamp.from(seenAt), vmId)
        .update();
  }

  @Override
  public void delete(UUID vmId) {
    jdbc.sql("delete from enrollment_token where vm_id = ?").param(vmId).update();
    jdbc.sql("delete from agent where vm_id = ?").param(vmId).update();
  }
}
