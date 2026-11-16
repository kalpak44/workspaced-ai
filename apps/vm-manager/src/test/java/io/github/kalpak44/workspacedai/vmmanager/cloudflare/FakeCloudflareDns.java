package io.github.kalpak44.workspacedai.vmmanager.cloudflare;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A CloudflareDns that keeps records in memory and counts every write, for testing the services
 * above it without HTTP. Setting {@link #failure} makes every call throw it, {@link #failingName}
 * only a write to that name.
 */
public class FakeCloudflareDns implements CloudflareDns {

  public final Map<String, DnsRecord> records = new ConcurrentSkipListMap<>();
  public final List<String> writes = new ArrayList<>();
  public volatile boolean configured = true;
  public volatile CloudflareException failure;
  public volatile String failingName;
  private final AtomicLong ids = new AtomicLong();

  public String add(String type, String name, String content) {
    var id = "r" + ids.incrementAndGet();
    records.put(id, new DnsRecord(id, type, name, content));
    return id;
  }

  @Override
  public boolean isConfigured() {
    return configured;
  }

  @Override
  public List<DnsRecord> records(String nameSuffix) {
    fail(null);
    return records.values().stream().filter(r -> r.name().endsWith(nameSuffix)).toList();
  }

  @Override
  public void create(String type, String name, String content, int ttl) {
    fail(name);
    writes.add("create " + type + " " + name);
    add(type, name, content);
  }

  @Override
  public void update(String id, String content) {
    var record = records.get(id);
    fail(record.name());
    writes.add("update " + record.type() + " " + record.name());
    records.put(id, new DnsRecord(id, record.type(), record.name(), content));
  }

  @Override
  public void delete(String id) {
    var record = records.get(id);
    fail(record.name());
    writes.add("delete " + record.type() + " " + record.name());
    records.remove(id);
  }

  private void fail(String name) {
    if (failure != null) {
      throw failure;
    }
    if (name != null && name.equals(failingName)) {
      throw new CloudflareException("Cloudflare answered 500 on " + name);
    }
  }
}
