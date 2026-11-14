package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A HetznerClient that keeps servers in memory, for testing the services above it without HTTP.
 * Setting {@link #failure} makes every call throw it.
 */
public class FakeHetznerClient implements HetznerClient {

  public final Map<Long, Server> servers = new ConcurrentHashMap<>();
  public final List<ServerSpec> created = new ArrayList<>();
  public final List<Long> deleted = new ArrayList<>();
  public volatile List<ServerType> types = List.of();
  public volatile HetznerException failure;
  public int typeCalls;
  private final AtomicLong ids = new AtomicLong(1000);

  /** Matches Hetzner's selector syntax as far as this app uses it: comma-joined key=value pairs. */
  static boolean matches(Map<String, String> labels, String selector) {
    return Arrays.stream(selector.split(","))
        .map(term -> term.split("=", 2))
        .allMatch(kv -> kv[1].equals(labels.get(kv[0])));
  }

  @Override
  public List<ServerType> serverTypes() {
    fail();
    typeCalls++;
    return types;
  }

  @Override
  public List<Server> servers(String labelSelector) {
    fail();
    return servers.values().stream().filter(s -> matches(s.labels(), labelSelector)).toList();
  }

  @Override
  public CreatedServer createServer(ServerSpec spec) {
    fail();
    var id = ids.incrementAndGet();
    var ip = "203.0.113." + (id % 250);
    created.add(spec);
    servers.put(
        id,
        new Server(
            id, spec.name(), spec.serverType(), ip, Instant.EPOCH, Map.copyOf(spec.labels())));
    return new CreatedServer(id, ip);
  }

  @Override
  public void deleteServer(long id) {
    fail();
    deleted.add(id);
    servers.remove(id);
  }

  private void fail() {
    if (failure != null) {
      throw failure;
    }
  }
}
