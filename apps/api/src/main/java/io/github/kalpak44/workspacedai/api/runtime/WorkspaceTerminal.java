package io.github.kalpak44.workspacedai.api.runtime;

import java.io.IOException;

/** One interactive TTY running inside a workspace. */
public interface WorkspaceTerminal extends AutoCloseable {

  /** Sends exactly these bytes to the shell's standard input. */
  void input(byte[] bytes) throws IOException;

  /** Tells the remote TTY its visible dimensions. */
  void resize(int columns, int rows);

  @Override
  void close();
}
