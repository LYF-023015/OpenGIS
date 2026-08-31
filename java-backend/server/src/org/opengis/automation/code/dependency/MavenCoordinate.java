/** 文件职责：code 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.code.dependency;

import java.nio.file.Path;

/** Strict group:artifact:version coordinate; dynamic and snapshot versions are rejected. */
public record MavenCoordinate(String groupId, String artifactId, String version) {
  public MavenCoordinate {
    if (!safe(groupId) || !safe(artifactId) || !safe(version)) {
      throw new IllegalArgumentException("Invalid Maven coordinate component");
    }
    if (version.endsWith("-SNAPSHOT") || version.contains("[") || version.contains("(")) {
      throw new IllegalArgumentException("Only fixed release dependency versions are allowed");
    }
  }

  public static MavenCoordinate parse(String value) {
    String[] parts = value == null ? new String[0] : value.strip().split(":", -1);
    if (parts.length != 3) throw new IllegalArgumentException("Expected group:artifact:version");
    return new MavenCoordinate(parts[0], parts[1], parts[2]);
  }

  public Path repositoryPath(String extension) {
    return Path.of(
        groupId.replace('.', '/'),
        artifactId,
        version,
        artifactId + "-" + version + "." + extension);
  }

  @Override
  public String toString() {
    return groupId + ":" + artifactId + ":" + version;
  }

  private static boolean safe(String value) {
    return value != null && value.matches("[A-Za-z0-9_.-]+");
  }
}
