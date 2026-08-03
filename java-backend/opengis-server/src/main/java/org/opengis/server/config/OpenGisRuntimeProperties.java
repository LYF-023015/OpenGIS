package org.opengis.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration owned by the Java Sidecar process. */
@ConfigurationProperties(prefix = "opengis.runtime")
public class OpenGisRuntimeProperties {
  private String version = "0.1.0";
  private String logDir = "";

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getLogDir() {
    return logDir;
  }

  public void setLogDir(String logDir) {
    this.logDir = logDir;
  }
}
