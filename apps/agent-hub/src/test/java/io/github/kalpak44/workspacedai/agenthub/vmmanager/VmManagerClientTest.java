package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class VmManagerClientTest {

  @Test
  void aManagerWithoutTheVersionedRouteStillReceivesTheLegacyReadyReport() {
    List<UUID> ready = new CopyOnWriteArrayList<>();
    var legacy =
        new VmManagerClient() {
          @Override
          public void ready(UUID vmId) {
            ready.add(vmId);
          }

          @Override
          public Heartbeat heartbeat(UUID vmId, VmMemory memory) {
            throw new UnsupportedOperationException();
          }

          @Override
          public byte[] harness(UUID vmId) {
            throw new UnsupportedOperationException();
          }
        };
    var vm = UUID.randomUUID();

    legacy.ready(vm, 7L);

    assertThat(ready).containsExactly(vm);
  }
}
