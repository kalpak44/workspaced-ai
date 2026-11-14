package io.github.kalpak44.workspacedai.agenthub.agent;

/**
 * A request from a VM's agent as it arrived, headers unparsed so a malformed one is refused like a
 * bad signature rather than as a bad request.
 *
 * @param path the request path as sent, context path included and query string excluded
 * @param body the raw body, empty when there was none
 */
public record SignedRequest(
    String vmId, String timestamp, String signature, String method, String path, byte[] body) {}
