package io.github.kalpak44.workspacedai.api.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceRepository {

  private final JdbcClient jdbc;

  public WorkspaceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Workspace> findAll() {
    return jdbc.sql("SELECT id, name FROM workspace ORDER BY created_at, id")
        .query(Workspace.class)
        .list();
  }

  public Workspace create(String name) {
    var workspace = new Workspace(UUID.randomUUID(), name);
    jdbc.sql("INSERT INTO workspace (id, name) VALUES (:id, :name)")
        .param("id", workspace.id())
        .param("name", workspace.name())
        .update();
    return workspace;
  }

  /** Empty when no workspace has that id. */
  public Optional<Workspace> rename(UUID id, String name) {
    var updated =
        jdbc.sql("UPDATE workspace SET name = :name WHERE id = :id")
            .param("id", id)
            .param("name", name)
            .update();
    return updated == 0 ? Optional.empty() : Optional.of(new Workspace(id, name));
  }

  /** False when no workspace has that id. */
  public boolean delete(UUID id) {
    return jdbc.sql("DELETE FROM workspace WHERE id = :id").param("id", id).update() > 0;
  }
}
