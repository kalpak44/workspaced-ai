package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "What one of the user's workspaces costs while it exists")
public record WorkspaceSpend(
    UUID id,
    @Schema(example = "research-box") String name,
    @Schema(description = "Its hourly charge over a month, in cents", example = "890")
        long monthlyCents,
    @Schema(
            description =
                "While it is paused for want of funds, when it is deleted unless the balance"
                    + " pays to start it again; null while it runs")
        Instant deletesAt) {}
