package io.github.kalpak44.workspacedai.agenthub.agent;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import io.github.kalpak44.workspacedai.agenthub.vmmanager.VmManagerClient;
import io.github.kalpak44.workspacedai.agenthub.vmmanager.VmMemory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks what an enrolled agent signs, then passes its report on to the VM manager. */
@Service
public class AgentService {

  private final AgentStore store;
  private final VmManagerClient vmManager;
  private final AgentHubProperties properties;
  private final Clock clock;
  private final JsonMapper json;

  public AgentService(
      AgentStore store,
      VmManagerClient vmManager,
      AgentHubProperties properties,
      Clock clock,
      JsonMapper json) {
    this.store = store;
    this.vmManager = vmManager;
    this.properties = properties;
    this.clock = clock;
    this.json = json;
  }

  /**
   * @throws SignatureRefusedException the request is not proven to come from the VM it names
   * @throws io.github.kalpak44.workspacedai.agenthub.vmmanager.VmNotFoundException the VM manager
   *     no longer has the VM
   */
  public void ready(SignedRequest request) {
    var vmId = authenticate(request);
    store.touch(vmId, clock.instant());
    vmManager.ready(vmId);
  }

  /**
   * As {@link #ready}, for the report sent every 30 seconds after it. The VM's memory reading is
   * passed on when the body carries a plausible one; any other body still counts as a heartbeat.
   *
   * @return the VM's status at the VM manager, so the VM learns when to restart its harness
   */
  public String heartbeat(SignedRequest request) {
    var vmId = authenticate(request);
    store.touch(vmId, clock.instant());
    return vmManager.heartbeat(vmId, memory(request.body()));
  }

  // A malformed reading must not fail the heartbeat, or the VM would read it as the hub being
  // down and stop learning its status.
  private VmMemory memory(byte[] body) {
    if (body.length == 0) {
      return null;
    }
    try {
      var memory = json.readTree(body).path("memory");
      var total = memory.path("totalBytes");
      var available = memory.path("availableBytes");
      if (!whole(total) || !whole(available)) {
        return null;
      }
      var reading = new VmMemory(total.asLong(), available.asLong());
      var plausible =
          reading.totalBytes() > 0
              && reading.availableBytes() >= 0
              && reading.availableBytes() <= reading.totalBytes();
      return plausible ? reading : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  /**
   * As {@link #ready}, for the VM fetching what its harness runs with.
   *
   * @return the VM manager's body unparsed, since it carries the VM's secret values
   */
  public byte[] harness(SignedRequest request) {
    var vmId = authenticate(request);
    store.touch(vmId, clock.instant());
    return vmManager.harness(vmId);
  }

  /**
   * Checks the handshake of a tunnel worker socket, which counts as the VM being seen; there is
   * nothing to pass on.
   *
   * @return the VM the handshake is proven to come from
   */
  public UUID connect(SignedRequest request) {
    var vmId = authenticate(request);
    store.touch(vmId, clock.instant());
    return vmId;
  }

  /** The VM the request is proven to come from. */
  UUID authenticate(SignedRequest request) {
    if (request.vmId() == null || request.timestamp() == null || request.signature() == null) {
      throw new SignatureRefusedException("the request is not signed");
    }
    var vmId = parseVmId(request.vmId());
    var key =
        store
            .publicKey(vmId)
            .orElseThrow(() -> new SignatureRefusedException("VM " + vmId + " has no agent"));
    // A bounded age stops a captured request being replayed later.
    var age = Duration.between(parseTimestamp(request.timestamp()), clock.instant()).abs();
    if (age.compareTo(properties.signatureSkew()) > 0) {
      throw new SignatureRefusedException("the timestamp is too far from this server's clock");
    }
    var message =
        String.join(
            "\n",
            request.method(),
            request.path(),
            request.timestamp(),
            HexFormat.of().formatHex(Hashes.sha256(request.body())));
    if (!Ed25519Keys.verify(
        Ed25519Keys.decode(key),
        message.getBytes(StandardCharsets.UTF_8),
        decodeSignature(request.signature()))) {
      throw new SignatureRefusedException("the signature does not verify");
    }
    return vmId;
  }

  private static boolean whole(JsonNode value) {
    return value.isIntegralNumber() && value.canConvertToLong();
  }

  private static UUID parseVmId(String vmId) {
    try {
      return UUID.fromString(vmId);
    } catch (IllegalArgumentException e) {
      throw new SignatureRefusedException("X-Agent-Vm is not a VM id");
    }
  }

  private static Instant parseTimestamp(String timestamp) {
    if (!timestamp.matches("\\d{1,12}")) {
      throw new SignatureRefusedException("X-Agent-Timestamp is not unix seconds");
    }
    return Instant.ofEpochSecond(Long.parseLong(timestamp));
  }

  private static byte[] decodeSignature(String signature) {
    try {
      return Base64.getDecoder().decode(signature);
    } catch (IllegalArgumentException e) {
      throw new SignatureRefusedException("X-Agent-Signature is not base64");
    }
  }
}
