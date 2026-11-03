package io.github.kalpak44.workspacedai.api.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A remote MCP server a workspace's agent can use. */
@Entity
@Table(name = "workspace_mcp_server")
public class McpServerEntity {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false, updatable = false)
  private UUID workspaceId;

  @Column(nullable = false, length = 64, updatable = false)
  private String name;

  @Column(nullable = false, length = 2000)
  private String url;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private Map<String, String> headers;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected McpServerEntity() {
    // For JPA.
  }

  public McpServerEntity(UUID workspaceId, String name, String url, Map<String, String> headers) {
    this.id = UUID.randomUUID();
    this.workspaceId = workspaceId;
    this.name = name;
    this.url = url;
    this.headers = Map.copyOf(headers);
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public String getName() {
    return name;
  }

  public String getUrl() {
    return url;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }
}
