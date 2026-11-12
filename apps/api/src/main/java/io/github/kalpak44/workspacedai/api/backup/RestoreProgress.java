package io.github.kalpak44.workspacedai.api.backup;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How far a workspace is through restoring a backup, while it starts")
public record RestoreProgress(
    @Schema(allowableValues = {"downloading", "checking", "extracting", "done"}) String phase,
    @Schema(description = "Bytes for downloading and checking, files for extracting") long done,
    long total,
    @Schema(description = "0 to 100, across all three phases", example = "64") int progress) {}
