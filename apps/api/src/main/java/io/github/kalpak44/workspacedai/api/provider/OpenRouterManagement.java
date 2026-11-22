package io.github.kalpak44.workspacedai.api.provider;

import java.util.UUID;

/**
 * Backend-only management credentials create trial inference keys; they never enter a workspace.
 */
public interface OpenRouterManagement {
  boolean configured();

  IssuedKey createTrial(UUID userId);

  void revoke(String hash);

  record IssuedKey(String value, String hash) {
    @Override
    public String toString() {
      return "IssuedKey[redacted]";
    }
  }
}
