package io.github.kalpak44.workspacedai.api.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "Notifications to mark read or unread")
public record ReadRequest(@NotNull @Size(max = 200) List<@NotNull UUID> ids, boolean read) {}
