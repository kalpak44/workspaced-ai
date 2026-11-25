package io.github.kalpak44.workspacedai.api.compute;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Whether the user is waiting to be emailed once a plan is back")
public record PlanWaiting(boolean waiting) {}
