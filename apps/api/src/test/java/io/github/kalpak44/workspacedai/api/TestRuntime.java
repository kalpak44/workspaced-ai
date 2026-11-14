package io.github.kalpak44.workspacedai.api;

import io.github.kalpak44.workspacedai.api.runtime.FakeWorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the VM runtime, so no test needs a VM manager. A test that chats sets
 * workspaced.workspace.local-harness.url and .password to the fake harness it started.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestRuntime {

  @Bean
  @Primary
  WorkspaceRuntime fakeWorkspaceRuntime(
      @Value("${workspaced.workspace.local-harness.url:#{null}}") URI harnessUrl,
      @Value("${workspaced.workspace.local-harness.password:#{null}}") String password) {
    return new FakeWorkspaceRuntime(harnessUrl, password);
  }
}
