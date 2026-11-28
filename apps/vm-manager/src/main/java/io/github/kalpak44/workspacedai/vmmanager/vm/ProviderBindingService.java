package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** One Vault CAS write binds the desired profile, credential and monotonic restart revision. */
@Service
public class ProviderBindingService {
  public record Receipt(Long revision, Long readyRevision) {}

  private final SecretStore store;
  private final VmRepository vms;

  public ProviderBindingService(SecretStore store, VmRepository vms) {
    this.store = store;
    this.vms = vms;
  }

  private void requireVm(UUID id) {
    if (vms.findById(id).isEmpty()) throw new VmNotFoundException(id);
  }

  public Receipt receipt(UUID id) {
    requireVm(id);
    var data = store.read(id, SecretStore.Entry.PROVIDER);
    return new Receipt(number(data.get("revision")), number(data.get("ready")));
  }

  private Long number(String value) {
    return value == null ? null : Long.valueOf(value);
  }

  public void request(UUID id, long revision, String provider, String key) {
    requireVm(id);
    if (revision < 1
        || !java.util.Set.of("openrouter", "openai", "openai-chatgpt").contains(provider)
        || key == null
        || key.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid provider binding");
    store.update(
        id,
        SecretStore.Entry.PROVIDER,
        current -> {
          var previous = number(current.get("revision"));
          if (previous != null
              && (previous > revision
                  || (previous == revision
                      && (!provider.equals(current.get("provider"))
                          || !key.equals(current.get("key"))))))
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Provider revision has been superseded");
          if (previous != null && previous == revision) return current;
          return Map.of("revision", Long.toString(revision), "provider", provider, "key", key);
        });
  }

  public boolean ready(UUID id, Long revision) {
    requireVm(id);
    var updated =
        store.update(
            id,
            SecretStore.Entry.PROVIDER,
            current -> {
              if (revision == null || !Objects.equals(number(current.get("revision")), revision))
                return current;
              var next = new HashMap<>(current);
              next.put("ready", Long.toString(revision));
              return next;
            });
    return revision != null
        && Objects.equals(number(updated.get("revision")), revision)
        && Objects.equals(number(updated.get("ready")), revision);
  }

  public record Configuration(Map<String, String> environment, Long revision) {
    @Override
    public String toString() {
      return "ProviderConfiguration[revision="
          + revision
          + ", variables="
          + new java.util.TreeSet<>(environment.keySet())
          + "]";
    }
  }

  public Configuration configuration(UUID id, Map<String, String> original) {
    var data = store.read(id, SecretStore.Entry.PROVIDER);
    if (data.isEmpty()) return new Configuration(original, null);
    var next = new HashMap<>(original);
    next.remove("OPENROUTER_API_KEY");
    next.remove("OPENAI_API_KEY");
    next.remove("OPENAI_BASE_URL");
    var provider = data.get("provider");
    if (!provider.equals("openrouter")) next.remove("OPENROUTER_BASE_URL");
    next.put("WORKSPACED_AI_PROVIDER", provider);
    if (!provider.equals("openai-chatgpt"))
      next.put(
          provider.equals("openai") ? "OPENAI_API_KEY" : "OPENROUTER_API_KEY", data.get("key"));
    if (provider.equals("openrouter"))
      next.putIfAbsent("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1");
    return new Configuration(Map.copyOf(next), number(data.get("revision")));
  }
}
