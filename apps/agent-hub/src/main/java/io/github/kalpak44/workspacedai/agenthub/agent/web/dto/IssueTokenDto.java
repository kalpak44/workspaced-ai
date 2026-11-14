package io.github.kalpak44.workspacedai.agenthub.agent.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** The VM the VM manager is about to create, and wants an enrollment token for. */
public record IssueTokenDto(@NotNull UUID vmId) {}
