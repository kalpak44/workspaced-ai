package io.github.kalpak44.workspacedai.api.domain;

/** How the user's records get made. */
public enum DomainMode {
  /** The user's DNS provider applies our template once they approve it. */
  DOMAIN_CONNECT,
  /** The user adds the records at their DNS provider themselves. */
  MANUAL
}
