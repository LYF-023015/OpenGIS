/** 文件职责：server 后端领域：Spring Boot 后端启动入口。 */
package org.opengis.server;

import java.util.ArrayList;
import java.util.List;
import org.apache.coyote.http11.Http11Nio2Protocol;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;

/** Java Sidecar process entry point. */
@SpringBootApplication(scanBasePackages = "org.opengis")
@ConfigurationPropertiesScan(basePackages = "org.opengis")
public class OpenGisApplication {

  public static void main(String[] args) {
    SpringApplication.run(OpenGisApplication.class, normalizeLegacyArguments(args));
  }

  /** 使用 JDK 异步通道启动 Tomcat，避开部分 Windows/JDK 组合中 Selector 唤醒管道不可用的问题。 */
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatProtocolCustomizer() {
    return factory -> factory.setProtocol(Http11Nio2Protocol.class.getName());
  }

  /** Maps the desktop launcher's concise arguments to Spring Boot properties. */
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
