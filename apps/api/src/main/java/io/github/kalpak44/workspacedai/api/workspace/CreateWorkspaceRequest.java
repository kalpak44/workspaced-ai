package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A saved personal connector or a one-off key for the workspace's VM. Without either the workspace
 * is a draft, launched once its key arrives.
 */
@Schema(description = "A new workspace: its name, its VM size and its model provider")
public record CreateWorkspaceRequest(
    @Schema(example = "Research", maxLength = 100) @NotBlank @Size(max = 100) String name,
    @Schema(description = "An id from /model-providers", example = "openrouter") @NotBlank
        String modelProvider,
    @Schema(
            description =
                "Checked with the provider before the workspace is created, then kept as its "
                    + "provider secret, which cannot be removed. Omitted, the workspace is a "
                    + "DRAFT with no VM until /workspaces/{id}/launch gives it one",
            example = "sk-or-v1-…",
            maxLength = 1000)
        // Absent makes a draft; present but blank is a mistake, not a request for one.
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        @Size(max = 1000)
        String apiKey,
    @Schema(description = "One of /vm-sizes; the default size when omitted", example = "cx23")
        @Size(max = 20)
        String serverType,
    @Schema(
            description =
                "An IANA time zone for its agent, usually the browser's; UTC when omitted",
            example = "Europe/Sofia")
        @Size(max = 64)
        String timeZone,
    @Schema(
            description =
                "The account's one free demo: the cheapest available size, never charged, and deleted a few"
                    + " hours after it starts. serverType is ignored",
            example = "false")
        Boolean demo,
    @Schema(description = "A saved connector owned by this user, instead of apiKey")
        java.util.UUID connectorId,
    @Schema(description = "Start without provider credentials and open ChatGPT connection setup")
        Boolean useChatGptPlan) {
  public CreateWorkspaceRequest(
      String name,
      String modelProvider,
      String apiKey,
      String serverType,
      String timeZone,
      Boolean demo,
      java.util.UUID connectorId) {
    this(name, modelProvider, apiKey, serverType, timeZone, demo, connectorId, false);
  }

  public CreateWorkspaceRequest(
      String name,
      String modelProvider,
      String apiKey,
      String serverType,
      String timeZone,
      Boolean demo) {
    this(name, modelProvider, apiKey, serverType, timeZone, demo, null);
  }

  /** Omitted means a workspace the balance pays for. */
  boolean wantsDemo() {
    return Boolean.TRUE.equals(demo);
  }

  /** Blank means the default size. */
  String serverTypeOrNull() {
    return serverType == null || serverType.isBlank() ? null : serverType.strip();
  }

  /** Blank means UTC. */
  String timeZoneOrUtc() {
    return timeZone == null || timeZone.isBlank() ? "UTC" : timeZone.strip();
  }

  /** Surrounding whitespace is never part of a name, so "Docs" and " Docs " are one. */
  String trimmedName() {
    return name.strip();
  }

  /** A key pasted with a trailing newline is the same key. Null asks for a draft. */
  String trimmedApiKey() {
    return apiKey == null ? null : apiKey.strip();
  }
}
