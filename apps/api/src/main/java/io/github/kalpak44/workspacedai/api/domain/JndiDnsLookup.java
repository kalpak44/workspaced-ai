package io.github.kalpak44.workspacedai.api.domain;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import org.springframework.stereotype.Component;

/**
 * Asks the configured public resolver through the JDK's own DNS client, never the cluster's, so a
 * name is seen as the internet sees it and an internal one cannot be reached by naming it.
 */
@Component
class JndiDnsLookup implements DnsLookup {

  private final DomainProperties properties;

  JndiDnsLookup(DomainProperties properties) {
    this.properties = properties;
  }

  // Stops above the top-level domain, which is never one a user can point anywhere.
  @Override
  public Optional<String> zoneOf(String name) {
    var labels = name.split("\\.");
    for (var i = 0; i < labels.length - 1; i++) {
      var candidate = String.join(".", List.of(labels).subList(i, labels.length));
      if (!values(candidate, "SOA").isEmpty()) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  @Override
  public List<String> txt(String name) {
    return values(name, "TXT").stream().map(JndiDnsLookup::unquote).toList();
  }

  @Override
  public List<String> ipv4(String name) {
    return values(name, "A");
  }

  private List<String> values(String name, String type) {
    var env = new Hashtable<String, String>();
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
    env.put(Context.PROVIDER_URL, properties.resolver());
    env.put("com.sun.jndi.dns.timeout.initial", Long.toString(properties.timeout().toMillis()));
    env.put("com.sun.jndi.dns.timeout.retries", "2");
    try {
      var context = new InitialDirContext(env);
      try {
        Attribute attribute = context.getAttributes(name + ".", new String[] {type}).get(type);
        var values = new ArrayList<String>();
        if (attribute != null) {
          for (var i = 0; i < attribute.size(); i++) {
            values.add(String.valueOf(attribute.get(i)));
          }
        }
        return values;
      } finally {
        context.close();
      }
    } catch (NameNotFoundException _) {
      return List.of();
    } catch (NamingException e) {
      throw new DnsLookupException("could not look up " + type + " for " + name, e);
    }
  }

  /** A TXT record of several strings arrives as {@code "a" "b"}; its text is them joined. */
  static String unquote(String record) {
    var text = new StringBuilder();
    var quoted = false;
    for (var c : record.toCharArray()) {
      if (c == '"') {
        quoted = !quoted;
      } else if (quoted || !Character.isWhitespace(c)) {
        text.append(c);
      }
    }
    return text.toString();
  }
}
