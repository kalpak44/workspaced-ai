package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.vm.SecretStore.Entry;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * A VM's secrets: the model provider's key, set when the VM is created and never removed, up to
 * {@link #MAX_SECRETS} of the user's own, and the harness's system environment, which the user
 * routes never show. The harness on the VM receives all three through {@link #environment}.
 */
@Service
public class VmSecretService {

  /** The one system secret: the OpenRouter key the workspace was created with. */
  public static final String SYSTEM_SECRET = "OPENROUTER_API_KEY";

  /** How many secrets a user may add to one VM, the system secret not counted. */
  public static final int MAX_SECRETS = 50;

  /** The harness password given to a VM whose system environment the API never handed over. */
  public static final String PASSWORD_VARIABLE = "OPENCODE_PASSWORD";

  /** The harness reads its settings from these, so a user secret must never set one. */
  public static final String RESERVED_PREFIX = "OPENCODE_";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final VmRepository vms;
  private final SecretStore store;

  public VmSecretService(VmRepository vms, SecretStore store) {
    this.vms = vms;
    this.store = store;
  }

  /** The system secret first, then the rest by name; the system environment is never listed. */
  public List<VmSecret> list(UUID vmId) {
    requireVm(vmId);
    var names = store.read(vmId, Entry.SECRETS).keySet();
    var system =
        names.contains(SYSTEM_SECRET)
            ? Stream.of(new VmSecret(SYSTEM_SECRET, true))
            : Stream.<VmSecret>empty();
    var user =
        names.stream()
            .filter(name -> !name.equals(SYSTEM_SECRET))
            .sorted()
            .map(name -> new VmSecret(name, false));
    return Stream.concat(system, user).toList();
  }

  /**
   * @throws SecretRefusedException when it is the system secret, would shadow the system
   *     environment, the VM already has one of that name, or it already holds as many as it may
   */
  public VmSecret add(UUID vmId, String name, String value) {
    requireVm(vmId);
    if (name.equals(SYSTEM_SECRET)) {
      throw new SecretRefusedException(name + " is set when the VM is created");
    }
    // The harness gets both entries merged with the system one winning, so the secret would be
    // silently ignored.
    if (name.startsWith(RESERVED_PREFIX) || store.read(vmId, Entry.SYSTEM).containsKey(name)) {
      throw new SecretRefusedException(name + " is set by the workspace and cannot be a secret");
    }
    store.update(
        vmId,
        Entry.SECRETS,
        current -> {
          if (current.containsKey(name)) {
            throw new SecretRefusedException("the VM already has a secret " + name);
          }
          var user = current.keySet().stream().filter(n -> !n.equals(SYSTEM_SECRET)).count();
          if (user >= MAX_SECRETS) {
            throw new SecretRefusedException(
                "a VM holds at most " + MAX_SECRETS + " secrets; remove one first");
          }
          var next = new HashMap<>(current);
          next.put(name, value);
          return next;
        });
    return new VmSecret(name, false);
  }

  /**
   * @throws SecretRefusedException when it is the system secret
   * @throws SecretNotFoundException when the VM has no secret of that name
   */
  public void remove(UUID vmId, String name) {
    requireVm(vmId);
    if (name.equals(SYSTEM_SECRET)) {
      throw new SecretRefusedException(name + " is the model provider's key and cannot be removed");
    }
    store.update(
        vmId,
        Entry.SECRETS,
        current -> {
          if (!current.containsKey(name)) {
            throw new SecretNotFoundException(vmId, name);
          }
          var next = new HashMap<>(current);
          next.remove(name);
          return next;
        });
  }

  /**
   * Everything the harness runs with: the user's secrets, the key, and the system environment on
   * top. A VM created before the system entry existed gets a password here, written with
   * check-and-set so two concurrent first calls agree on one. Only an empty entry gets one: the API
   * names its own password variable, and a second password beside it would be ambiguous.
   */
  public Map<String, String> environment(UUID vmId) {
    requireVm(vmId);
    var system =
        store.update(
            vmId,
            Entry.SYSTEM,
            current -> {
              if (!current.isEmpty()) {
                return current;
              }
              return Map.of(PASSWORD_VARIABLE, password());
            });
    var merged = new HashMap<>(store.read(vmId, Entry.SECRETS));
    merged.putAll(system);
    return Map.copyOf(merged);
  }

  /**
   * Called by {@link VmService} for a VM it is about to create, so before it exists. The key field
   * is the only source of the key, so an environment entry of that name is dropped.
   */
  void init(UUID vmId, String openRouterApiKey, Map<String, String> environment) {
    store.update(vmId, Entry.SECRETS, _ -> Map.of(SYSTEM_SECRET, openRouterApiKey));
    var system = new HashMap<>(environment);
    system.remove(SYSTEM_SECRET);
    store.update(vmId, Entry.SYSTEM, _ -> system);
  }

  /** Called by {@link VmService} once the VM's server is gone. */
  void deleteAll(UUID vmId) {
    store.deleteAll(vmId);
  }

  void requireVm(UUID vmId) {
    if (vms.findById(vmId).isEmpty()) {
      throw new VmNotFoundException(vmId);
    }
  }

  // 32 random bytes, as the API generates it for a workspace it creates.
  private static String password() {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
