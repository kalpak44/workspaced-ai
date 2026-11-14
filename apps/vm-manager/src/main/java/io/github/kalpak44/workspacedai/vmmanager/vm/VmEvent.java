package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.util.UUID;

/**
 * What {@link VmService} publishes after each change, as Spring application events. The service
 * does not know who listens; the event stream is one listener.
 */
public sealed interface VmEvent {

  /** A VM was created or changed; carries it as it now is. */
  record Changed(Vm vm) implements VmEvent {}

  record Deleted(UUID id) implements VmEvent {}
}
