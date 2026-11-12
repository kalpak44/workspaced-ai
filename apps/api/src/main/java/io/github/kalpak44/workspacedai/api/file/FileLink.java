package io.github.kalpak44.workspacedai.api.file;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;

@Schema(description = "A download link that works without signing in, until it expires")
public record FileLink(URI url, Instant expiresAt) {}
