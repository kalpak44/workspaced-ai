package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.Map;
import java.util.TreeSet;

/**
 * What a VM's supervisor runs: the image, and the environment it starts the container with.
 *
 * @param environment secret values; {@link #toString} shows only their names
 */
public record Harness(String image, Map<String, String> environment) {

  @Override
  public String toString() {
    return "Harness[image=" + image + ", environment=" + new TreeSet<>(environment.keySet()) + "]";
  }
}
