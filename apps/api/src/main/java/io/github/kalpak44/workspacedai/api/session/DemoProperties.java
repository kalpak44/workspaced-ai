package io.github.kalpak44.workspacedai.api.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Pacing for the scripted agent. Tests shorten both; the defaults make the stream readable when
 * watched by hand.
 *
 * @param tokenDelay pause between streamed tokens and output lines
 * @param promptTimeout how long the agent waits on an unanswered question or approval
 */
@ConfigurationProperties("workspaced.demo")
public record DemoProperties(
    @DefaultValue("50ms") Duration tokenDelay, @DefaultValue("10m") Duration promptTimeout) {}
