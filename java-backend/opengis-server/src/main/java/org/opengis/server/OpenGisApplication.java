package org.opengis.server;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Java Sidecar process entry point. */
@SpringBootApplication(scanBasePackages = "org.opengis")
@ConfigurationPropertiesScan(basePackages = "org.opengis")
public class OpenGisApplication {

  public static void main(String[] args) {
    SpringApplication.run(OpenGisApplication.class, normalizeLegacyArguments(args));
  }

  /** Keeps Phase 1 compatible with the Python Sidecar command-line shape. */
  static String[] normalizeLegacyArguments(String[] args) {
    List<String> normalized = new ArrayList<>();
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if ("--host".equals(argument) && index + 1 < args.length) {
        normalized.add("--server.address=" + args[++index]);
      } else if ("--port".equals(argument) && index + 1 < args.length) {
        normalized.add("--server.port=" + args[++index]);
      } else if ("--log-dir".equals(argument) && index + 1 < args.length) {
        String value = args[++index];
        normalized.add("--opengis.runtime.log-dir=" + value);
        normalized.add("--logging.file.path=" + value);
      } else if (argument.startsWith("--host=")) {
        normalized.add("--server.address=" + argument.substring("--host=".length()));
      } else if (argument.startsWith("--port=")) {
        normalized.add("--server.port=" + argument.substring("--port=".length()));
      } else if (argument.startsWith("--log-dir=")) {
        String value = argument.substring("--log-dir=".length());
        normalized.add("--opengis.runtime.log-dir=" + value);
        normalized.add("--logging.file.path=" + value);
      } else {
        normalized.add(argument);
      }
    }
    return normalized.toArray(String[]::new);
  }
}
