package io.github.kalpak44.workspacedai.agenthub.tunnel;

import java.nio.ByteBuffer;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.JsonNode;

/** The exchange a worker carries, told what the supervisor sends on the worker's own thread. */
interface WorkerListener {

  /** A JSON control message: response, open, error or end. */
  void control(JsonNode message);

  /** Part of a binary message, as it arrived; the buffer is only valid during the call. */
  void payload(ByteBuffer part, boolean last);

  void closed(CloseStatus status);
}
