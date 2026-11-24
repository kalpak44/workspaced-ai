package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.List;

/** A create asked for a cloud this VM manager cannot create servers in. */
public class ComputeProviderNotOfferedException extends RuntimeException {

  public ComputeProviderNotOfferedException(String provider, List<String> offered) {
    super("provider " + provider + " is not offered; choose one of " + String.join(", ", offered));
  }
}
