package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * A VM's secrets: the model provider's key, set when the VM is created and never removed, and up to
 * {@link #MAX_SECRETS} of the user's own. Nothing on a VM reads them yet; they are only kept.
 */
@Service
public class VmSecretService {

  /** The one system secret: the OpenRouter key the workspace was created with. */
  public static final String SYSTEM_SECRET = "OPENROUTER_API_KEY";

  /** How many secrets a user may add to one VM, the system secret not counted. */
  public static final int MAX_SECRETS = 50;

  private final VmRepository vms;
  private final SecretStore store;

  public VmSecretService(VmRepository vms, SecretStore store) {
    this.vms = vms;
    this.store = store;
  }

  /** The system secret first, then the rest by name. */
  public List<VmSecret> list(UUID vmId) {
    requireVm(vmId);
    var names = store.names(vmId);
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
   * @throws SecretRefusedException when it is the system secret, the VM already has one of that
   *     name, or it already holds as many as it may
   */
  public VmSecret add(UUID vmId, String name, String value) {
    requireVm(vmId);
    if (name.equals(SYSTEM_SECRET)) {
      throw new SecretRefusedException(name + " is set when the VM is created");
    }
    store.update(
        vmId,
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
        current -> {
          if (!current.containsKey(name)) {
            throw new SecretNotFoundException(vmId, name);
          }
          var next = new HashMap<>(current);
          next.remove(name);
          return next;
        });
  }

  /** Called by {@link VmService} for a VM it is about to create, so before it exists. */
  void init(UUID vmId, String openRouterApiKey) {
    store.update(vmId, _ -> Map.of(SYSTEM_SECRET, openRouterApiKey));
  }

  /** Called by {@link VmService} once the VM's server is gone. */
  void deleteAll(UUID vmId) {
    store.deleteAll(vmId);
  }

  private void requireVm(UUID vmId) {
    if (vms.findById(vmId).isEmpty()) {
      throw new VmNotFoundException(vmId);
    }
  }
}
