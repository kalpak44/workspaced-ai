package io.github.kalpak44.workspacedai.api.domain;

/**
 * A DNS provider that applies our template itself.
 *
 * @param name what the user knows it as, e.g. GoDaddy
 * @param syncUrl where the user is sent to approve a template; ours names the rest of the path
 */
public record DnsProvider(String name, String syncUrl) {}
