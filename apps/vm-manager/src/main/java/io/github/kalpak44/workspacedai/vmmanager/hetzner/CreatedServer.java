package io.github.kalpak44.workspacedai.vmmanager.hetzner;

/**
 * A server Hetzner has accepted; it is still booting.
 *
 * @param ipv4 its public address, assigned at creation; null if Hetzner sent none
 * @param ipv6 the address it answers on in its public /64; null if Hetzner sent none
 */
public record CreatedServer(long id, String ipv4, String ipv6) {}
