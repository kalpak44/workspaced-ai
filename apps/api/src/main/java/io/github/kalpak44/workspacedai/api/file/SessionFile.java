package io.github.kalpak44.workspacedai.api.file;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A file uploaded into a session's folder")
public record SessionFile(
    @Schema(example = "report.pdf") String name,
    @Schema(description = "Relative to the session's folder", example = "uploads/report.pdf")
        String path,
    long size) {}
