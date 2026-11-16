package io.github.kalpak44.workspacedai.vmmanager.vm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param domain every VM is published as {@code <vm id>.<domain>}; everything of that shape under
 *     it is this app's to rewrite and delete
 * @param ttl seconds a resolver may keep an answer; short, so a changed address is seen soon
 */
@ConfigurationProperties("workspaced.vm-dns")
public record VmDnsProperties(
    @DefaultValue("vm.workspaced.online") String domain, @DefaultValue("60") int ttl) {}
