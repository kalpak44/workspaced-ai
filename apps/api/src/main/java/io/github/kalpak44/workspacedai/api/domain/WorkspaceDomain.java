package io.github.kalpak44.workspacedai.api.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A name pointed at a workspace")
public record WorkspaceDomain(
    @Schema(description = "Assigned by the server") UUID id,
    @Schema(description = "The full name", example = "app.example.com") String name,
    @Schema(description = "The zone it lives in", example = "example.com") String domain,
    @Schema(description = "The name under the zone; @ for the zone itself", example = "app")
        String host,
    @Schema(
            description = "Whether the DNS provider applies the records or the user adds them",
            allowableValues = {"DOMAIN_CONNECT", "MANUAL"})
        String mode,
    @Schema(
            description = "ACTIVE once public DNS resolves it to the workspace",
            allowableValues = {"PENDING", "ACTIVE"})
        String status,
    @Schema(description = "The user's DNS provider; null unless it applies the records itself")
        String providerName,
    @Schema(
            description =
                "Where the user approves the records at their DNS provider; null once active or"
                    + " in manual mode")
        String connectUrl,
    @Schema(description = "The records the name needs") List<DnsRecordView> records,
    Instant createdAt,
    @Schema(description = "When public DNS was last seen pointing it here; null before")
        Instant verifiedAt) {}
