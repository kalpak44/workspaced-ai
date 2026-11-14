package io.github.kalpak44.workspacedai.agenthub.agent;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import io.github.kalpak44.workspacedai.agenthub.vmmanager.VmManagerClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Checks what an enrolled agent signs, then passes its report on to the VM manager. */
@Service
public class AgentService {

  private final AgentStore store;
  private final VmManagerClient vmManager;
  private final AgentHubProperties properties;
  private final Clock clock;

  public AgentService(
      AgentStore store, VmManagerClient vmManager, AgentHubProperties properties, Clock clock) {
    this.store = store;
    this.vmManager = vmManager;
    this.properties = properties;
    this.clock = clock;
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

  /** As {@link #ready}, for the report sent every 30 seconds after it. */
  public void heartbeat(SignedRequest request) {
    var vmId = authenticate(request);
    store.touch(vmId, clock.instant());
    vmManager.heartbeat(vmId);
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
