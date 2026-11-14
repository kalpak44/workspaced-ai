package io.github.kalpak44.workspacedai.vmmanager.hetzner;

/**
 * A server Hetzner has accepted; it is still booting.
 *
 * @param ipv4 its public address, assigned at creation; null if Hetzner sent none
 */
public record CreatedServer(long id, String ipv4) {}
