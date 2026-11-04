package io.github.kalpak44.workspacedai.api.harness;

/** Agent discovery metadata, excluding system prompts and permission configuration. */
public record HarnessAgent(
    String id, String name, String description, String mode, boolean hidden) {}
