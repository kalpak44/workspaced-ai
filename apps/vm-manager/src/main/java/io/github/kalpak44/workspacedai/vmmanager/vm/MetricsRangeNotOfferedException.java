package io.github.kalpak44.workspacedai.vmmanager.vm;

/** A metrics range was asked for that is not one of {@link MetricsRange}. */
public class MetricsRangeNotOfferedException extends RuntimeException {

  public MetricsRangeNotOfferedException(String range, String offered) {
    super("range " + range + " is not offered; use one of " + offered);
  }
}
