package io.github.kalpak44.workspacedai.api.compute;

import java.util.Arrays;
import java.util.Optional;

/**
 * The clouds a workspace's VM can run in. Hardcoded: adding one means the VM manager can create,
 * stop and firewall a server there, which is code, not configuration. Not offered to users yet, so
 * every workspace is created in {@link #DEFAULT}.
 */
public enum ComputeProvider {
  HETZNER("hetzner", "Hetzner Cloud");

  /** Where a workspace runs when nothing chose otherwise. */
  public static final ComputeProvider DEFAULT = HETZNER;

  private final String id;
  private final String displayName;

  ComputeProvider(String id, String displayName) {
    this.id = id;
    this.displayName = displayName;
  }

  public static Optional<ComputeProvider> byId(String id) {
    return Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst();
  }

  /** What the database and the VM manager know it by. */
  public String id() {
    return id;
  }

  public String displayName() {
    return displayName;
  }
}
