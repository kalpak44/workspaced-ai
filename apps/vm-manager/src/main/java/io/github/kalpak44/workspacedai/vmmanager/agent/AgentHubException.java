package io.github.kalpak44.workspacedai.vmmanager.agent;

/** The agent hub could not be asked, or did not answer as expected. */
public class AgentHubException extends RuntimeException {

  public AgentHubException(String message) {
    super(message);
  }

  public AgentHubException(String message, Throwable cause) {
    super(message, cause);
  }
}
