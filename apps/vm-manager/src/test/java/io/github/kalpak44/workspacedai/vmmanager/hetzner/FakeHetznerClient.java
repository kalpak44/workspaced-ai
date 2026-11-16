package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A HetznerClient that keeps servers in memory, for testing the services above it without HTTP.
 * Setting {@link #failure} makes every call throw it, {@link #createFailure} only a server create.
 */
public class FakeHetznerClient implements HetznerClient {

  public final Map<Long, Server> servers = new ConcurrentHashMap<>();
  public final List<ServerSpec> created = new ArrayList<>();
  public final List<Long> deleted = new ArrayList<>();
  public final Map<Long, Firewall> firewalls = new ConcurrentHashMap<>();
  public final List<Long> deletedFirewalls = new ArrayList<>();
  public volatile List<ServerType> types = List.of();
  public volatile List<ServerType.Price> ipv4Prices = List.of();
  public volatile HetznerException failure;
  public volatile HetznerException createFailure;
  public volatile ServerMetrics metrics = new ServerMetrics(List.of(), Map.of());
  public final List<String> metricsCalls = new ArrayList<>();
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
  public List<ServerType.Price> primaryIpv4Prices() {
    fail();
    return ipv4Prices;
  }

  @Override
  public List<Server> servers(String labelSelector) {
    fail();
    return servers.values().stream().filter(s -> matches(s.labels(), labelSelector)).toList();
  }

  @Override
  public CreatedServer createServer(ServerSpec spec) {
    fail();
    if (createFailure != null) {
      throw createFailure;
    }
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

  @Override
  public long createFirewall(String name, Map<String, String> labels) {
    fail();
    var id = ids.incrementAndGet();
    firewalls.put(id, new Firewall(id, name, Instant.EPOCH, Map.copyOf(labels), List.of(), 0));
    return id;
  }

  @Override
  public List<Firewall> firewalls(String labelSelector) {
    fail();
    return firewalls.values().stream().filter(f -> matches(f.labels(), labelSelector)).toList();
  }

  @Override
  public void setFirewallRules(long id, List<FirewallRule> rules) {
    fail();
    firewalls.computeIfPresent(
        id,
        (key, f) ->
            new Firewall(
                f.id(), f.name(), f.created(), f.labels(), List.copyOf(rules), f.servers()));
  }

  @Override
  public void deleteFirewall(long id) {
    fail();
    deletedFirewalls.add(id);
    firewalls.remove(id);
  }

  @Override
  public ServerMetrics metrics(long id, Instant start, Instant end, Duration step) {
    fail();
    metricsCalls.add(id + " " + start + " " + end + " " + step);
    return metrics;
  }

  private void fail() {
    if (failure != null) {
      throw failure;
    }
  }
}
