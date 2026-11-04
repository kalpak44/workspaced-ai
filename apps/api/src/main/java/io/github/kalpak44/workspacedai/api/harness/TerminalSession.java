package io.github.kalpak44.workspacedai.api.harness;

/** An OpenCode PTY. It outlives browser connections, but not a workspace runtime restart. */
public record TerminalSession(
    String id,
    String title,
    String command,
    String cwd,
    String status,
    long pid,
    Integer exitCode) {}
