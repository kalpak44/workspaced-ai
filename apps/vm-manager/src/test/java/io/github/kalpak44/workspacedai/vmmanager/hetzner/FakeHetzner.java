package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Answers /server_types as Hetzner does, in two pages, so no test calls the real API. The types and
 * prices are a trimmed copy of a real answer.
 */
public class FakeHetzner implements AutoCloseable {

  static final String PAGE_1 =
      """
      {"server_types":[
        {"name":"cx23","cores":2,"memory":4.0,"disk":40,"cpu_type":"shared",
         "architecture":"x86","deprecation":null,
         "prices":[{"location":"nbg1","price_monthly":{"net":"5.4900000000","gross":"6.5880000000000000"}},
                   {"location":"hel1","price_monthly":{"net":"5.4900000000","gross":"6.5880000000000000"}}]},
        {"name":"cx22","cores":2,"memory":4.0,"disk":40,"cpu_type":"shared",
         "architecture":"x86","deprecation":{"announced":"2025-10-01T00:00:00Z"},
         "prices":[{"location":"nbg1","price_monthly":{"net":"3.7900000000","gross":"4.5101000000000000"}}]}
      ],"meta":{"pagination":{"page":1,"next_page":2}}}
      """;

  static final String PAGE_2 =
      """
      {"server_types":[
        {"name":"cx33","cores":4,"memory":8.0,"disk":80,"cpu_type":"shared",
         "architecture":"x86","deprecation":null,
         "prices":[{"location":"nbg1","price_monthly":{"net":"8.4900000000","gross":"10.1880000000000000"}}]},
        {"name":"cx43","cores":8,"memory":16.0,"disk":160,"cpu_type":"shared",
         "architecture":"x86","deprecation":null,
         "prices":[{"location":"hel1","price_monthly":{"net":"15.9900000000","gross":"19.1880000000000000"}}]}
      ],"meta":{"pagination":{"page":2,"next_page":null}}}
      """;

  private final HttpServer server;
  public final List<String> authorizations = new CopyOnWriteArrayList<>();
  public final AtomicInteger requests = new AtomicInteger();
  public volatile int status = 200;

  public FakeHetzner() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/server_types",
        exchange -> {
          requests.incrementAndGet();
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          var page = exchange.getRequestURI().getQuery().contains("page=2") ? PAGE_2 : PAGE_1;
          var body = (status == 200 ? page : "{\"error\":{}}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  public URI url() {
    return URI.create("http://localhost:" + server.getAddress().getPort());
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
