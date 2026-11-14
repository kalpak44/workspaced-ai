package io.github.kalpak44.workspacedai.vmmanager.vm;

/** No harness image is configured, so there is nothing a VM could run. */
public class HarnessNotConfiguredException extends RuntimeException {

  public HarnessNotConfiguredException() {
    super("no harness image is configured");
  }
}
