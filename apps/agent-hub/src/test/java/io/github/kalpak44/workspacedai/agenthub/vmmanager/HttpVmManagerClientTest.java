package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpVmManagerClientTest {

  final FakeVmManager fake = new FakeVmManager();
  final UUID vm = UUID.randomUUID();

  HttpVmManagerClient client(URI url) {
    var properties =
        new AgentHubProperties(
            url, Duration.ofMinutes(30), Duration.ofSeconds(60), Duration.ofSeconds(2));
    return new HttpVmManagerClient(properties, HttpClient.newHttpClient());
  }

  @AfterEach
  void stop() {
    fake.close();
  }

  @Test
  void postsBothReports() {
    var client = client(URI.create(fake.url() + "/"));
    client.ready(vm);
    client.heartbeat(vm);
    assertThat(fake.reports).containsExactly("ready " + vm, "heartbeat " + vm);
  }

  @Test
  void tellsAVmItNoLongerHasFromAFailure() {
    var client = client(fake.url());
    fake.gone.add(vm);
    assertThatThrownBy(() -> client.heartbeat(vm)).isInstanceOf(VmNotFoundException.class);
    fake.status = 500;
    assertThatThrownBy(() -> client.ready(vm)).isInstanceOf(VmManagerException.class);
  }

  @Test
  void failsWhenItCannotBeReached() {
    var url = fake.url();
    fake.close();
    assertThatThrownBy(() -> client(url).ready(vm)).isInstanceOf(VmManagerException.class);
  }
}
