package io.github.kalpak44.workspacedai.api.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param image what each workspace pod runs; a placeholder that waits until it is deleted
 */
@ConfigurationProperties("workspaced.workspace")
public record WorkspaceProperties(@DefaultValue("busybox:1.38.0") String image) {}
