package io.github.kalpak44.workspacedai.api.harness;

import tools.jackson.databind.JsonNode;

/** A pending OpenCode question, retaining the upstream field schema for the browser. */
public record AgentForm(String id, String title, JsonNode fields) {}
