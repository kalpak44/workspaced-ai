package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderBindingServiceTest {
  @Test
  void chatGptProfileRemovesApiKeysAndNeverExportsOAuthTokensAsEnvironmentVariables() {
    var id = UUID.randomUUID();
    var repository = mock(VmRepository.class);
    when(repository.findById(id))
        .thenReturn(
            Optional.of(
                new Vm(
                    id,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "cx23",
                    1,
                    VmStatus.READY,
                    null,
                    null,
                    Instant.now(),
                    null)));
    var store = new InMemorySecretStore();
    var service = new ProviderBindingService(store, repository);
    service.request(id, 1, "openai-chatgpt", "private-oauth-snapshot");
    var original =
        Map.of(
            "OPENAI_API_KEY",
            "old-openai",
            "OPENROUTER_API_KEY",
            "old-router",
            "OPENAI_BASE_URL",
            "https://api.openai.com/v1",
            "OPENROUTER_BASE_URL",
            "https://openrouter.ai/api/v1",
            "CUSTOM",
            "keep");
    var config = service.configuration(id, original);
    assertThat(config.environment())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("CUSTOM", "keep", "WORKSPACED_AI_PROVIDER", "openai-chatgpt"));
    assertThat(config.toString()).doesNotContain("private-oauth-snapshot");
    service.request(id, 2, "openai", "new-api-key");
    assertThat(service.configuration(id, original).environment())
        .containsEntry("OPENAI_API_KEY", "new-api-key")
        .doesNotContainKey("OPENROUTER_API_KEY");
  }
}
