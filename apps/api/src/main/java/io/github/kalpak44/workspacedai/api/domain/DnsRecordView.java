package io.github.kalpak44.workspacedai.api.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DnsRecord", description = "One record at the user's DNS provider")
public record DnsRecordView(
    @Schema(
            example = "CNAME",
            allowableValues = {"A", "AAAA", "CNAME"})
        String type,
    @Schema(
            description = "The name as a DNS provider's form asks for it: @ for the domain itself",
            example = "app")
        String host,
    @Schema(description = "The full name", example = "app.example.com") String name,
    @Schema(
            description = "What it points at",
            example = "8d1e4b0a-2f6c-4f7e-9b1d-0c3a5e7f9a21.vm.workspaced.online")
        String value) {}
