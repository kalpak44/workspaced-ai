package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.util.List;
import java.util.Map;

/**
 * A server to create.
 *
 * @param name a hostname, unique within the project
 * @param sshKeys names or ids of project SSH keys to install; may be empty
 * @param image the image name; ignored when the client is configured to pick a snapshot by label
 * @param labels what the server is for; the VM manager rebuilds its inventory from them
 * @param userData cloud-config the server reads on its first boot; null for none
 */
public record ServerSpec(
    String name,
    String serverType,
    String location,
    String image,
    List<String> sshKeys,
    Map<String, String> labels,
    String userData) {}
