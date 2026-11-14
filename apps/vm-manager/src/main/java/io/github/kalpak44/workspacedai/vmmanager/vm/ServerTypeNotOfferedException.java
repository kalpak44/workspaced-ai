package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.List;

/** A create asked for a server type that is not among the ones offered. */
public class ServerTypeNotOfferedException extends RuntimeException {

  public ServerTypeNotOfferedException(String serverType, List<String> offered) {
    super(
        "server type "
            + serverType
            + " is not offered; choose one of "
            + String.join(", ", offered));
  }
}
