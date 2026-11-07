package io.github.kalpak44.workspacedai.api.variable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The .env file as the harness's start script reads it: NAME=value lines, the value taken as it is,
 * without expansion, after one pair of quotes around it is removed. Blank lines and lines starting
 * with # are skipped, and an {@code export } in front is allowed. Of a name given twice, the first
 * counts, as there.
 */
final class EnvFile {

  private EnvFile() {}

  static List<Variable> parse(String text) {
    var variables = new LinkedHashMap<String, String>();
    for (var raw : text.split("\n", -1)) {
      var line = raw.stripLeading();
      if (line.startsWith("export ")) line = line.substring("export ".length());
      if (line.isEmpty() || line.startsWith("#")) continue;
      var equals = line.indexOf('=');
      if (equals < 0) continue;
      var name = line.substring(0, equals);
      if (!name.matches(Variable.NAME_PATTERN) || name.startsWith("OPENCODE_")) continue;
      variables.putIfAbsent(name, unquote(line.substring(equals + 1)));
    }
    var result = new ArrayList<Variable>();
    variables.forEach((name, value) -> result.add(new Variable(name, value)));
    return List.copyOf(result);
  }

  /** Every value in double quotes, so one that is itself quoted reads back the same. */
  static String format(List<Variable> variables) {
    var text = new StringBuilder();
    for (var variable : variables) {
      text.append(variable.name()).append("=\"").append(variable.value()).append("\"\n");
    }
    return text.toString();
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && (value.startsWith("\"") && value.endsWith("\"")
            || value.startsWith("'") && value.endsWith("'"))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
