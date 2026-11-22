package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Where the guided tour is. The web app's bookmark only: nothing here is trusted, and every id in
 * it is checked against its owner wherever it is used.
 */
@Schema(description = "Where the account's guided tour is, so any browser resumes it")
public record TourProgress(
    @Schema(example = "4", minimum = "0", maximum = "100") @NotNull @Min(0) @Max(100)
        Integer step,
    @Schema(description = "The demo workspace, once launched", maxLength = 100) @Size(max = 100)
        String workspaceId,
    @Schema(description = "The session the tour builds in", maxLength = 100) @Size(max = 100)
        String sessionId,
    @Schema(description = "The demo draft, before it is launched", maxLength = 100)
        @Size(max = 100)
        String draftId,
    @Schema(description = "The name chosen for the demo", maxLength = 100) @Size(max = 100)
        String name,
    @Schema(description = "Skipped for now: shown again only when the user resumes it")
        boolean paused) {}
