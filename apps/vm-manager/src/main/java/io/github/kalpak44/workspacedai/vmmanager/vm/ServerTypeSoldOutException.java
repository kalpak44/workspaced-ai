package io.github.kalpak44.workspacedai.vmmanager.vm;

/** Hetzner has no capacity for the server type in the location right now. */
public class ServerTypeSoldOutException extends RuntimeException {

  public ServerTypeSoldOutException(String serverType) {
    super("server type " + serverType + " is sold out at Hetzner right now");
  }
}
