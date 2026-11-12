package io.github.kalpak44.workspacedai.api.backup;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;

@Schema(description = "A link the browser downloads the archive from, straight from storage")
public record BackupDownload(
    @Schema(description = "Signed; works without signing in, until it expires") URI url,
    Instant expiresAt) {}
