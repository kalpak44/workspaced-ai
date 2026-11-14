package io.github.kalpak44.workspacedai.api.webserver;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WebDomainStore {
  public record Domain(String name, UUID workspaceId, boolean enabled, boolean primaryDomain) {}

  private final JdbcTemplate jdbc;

  public WebDomainStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Domain> list(UUID workspace) {
    return jdbc.query(
        "SELECT * FROM workspace_web_domain WHERE workspace_id = ? ORDER BY name",
        (rs, _) ->
            new Domain(
                rs.getString("name"),
                rs.getObject("workspace_id", UUID.class),
                rs.getBoolean("enabled"),
                rs.getBoolean("primary_domain")),
        workspace);
  }

  public Optional<Domain> find(String name) {
    return jdbc
        .query(
            "SELECT * FROM workspace_web_domain WHERE name = ?",
            (rs, _) ->
                new Domain(
                    rs.getString("name"),
                    rs.getObject("workspace_id", UUID.class),
                    rs.getBoolean("enabled"),
                    rs.getBoolean("primary_domain")),
            name)
        .stream()
        .findFirst();
  }

  public void claim(UUID workspace, String name) {
    jdbc.update(
        "INSERT INTO workspace_web_domain(name, workspace_id) VALUES (?, ?)", name, workspace);
  }

  public void enabled(String name, boolean enabled) {
    jdbc.update("UPDATE workspace_web_domain SET enabled = ? WHERE name = ?", enabled, name);
  }

  public void delete(String name) {
    jdbc.update("DELETE FROM workspace_web_domain WHERE name = ?", name);
  }

  @Transactional
  public void primary(UUID workspace, String name) {
    jdbc.update(
        "DELETE FROM workspace_web_domain WHERE workspace_id = ? AND primary_domain AND name <> ?",
        workspace,
        name);
    jdbc.update(
        "UPDATE workspace_web_domain SET enabled = true, primary_domain = true WHERE workspace_id = ? AND name = ?",
        workspace,
        name);
  }
}
