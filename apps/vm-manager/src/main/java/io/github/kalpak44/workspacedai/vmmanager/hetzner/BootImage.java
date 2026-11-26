package io.github.kalpak44.workspacedai.vmmanager.hetzner;

/**
 * What a new server boots from.
 *
 * @param image the image name or snapshot id to create it with
 * @param hostFirewall whether the image filters inbound traffic itself, with the rules its
 *     supervisor is handed; one that does not must be put behind a Cloud Firewall of its own
 */
public record BootImage(String image, boolean hostFirewall) {}
