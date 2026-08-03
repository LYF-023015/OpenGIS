package org.opengis.code.dependency;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.opengis.platform.persistence.JsonFileStore;
import tools.jackson.databind.node.ObjectNode;

/** Approval-gated fixed-version dependency cache with checksum, repository and license records. */
public final class DependencyResolver {
  private static final Set<String> ALLOWED_GROUP_PREFIXES =
      Set.of("org.locationtech.", "org.apache.commons", "com.github.", "tools.jackson.");

  public List<ResolvedDependency> resolve(Path workspace, List<Request> requests, boolean offline) {
    List<ResolvedDependency> values = new ArrayList<>();
    for (Request request : requests == null ? List.<Request>of() : requests) {
      MavenCoordinate coordinate = MavenCoordinate.parse(request.coordinate());
      if (ALLOWED_GROUP_PREFIXES.stream().noneMatch(coordinate.groupId()::startsWith)) {
        throw new IllegalArgumentException(
            "Dependency group is not allowlisted: " + coordinate.groupId());
      }
      if (!request.approved()) {
        throw new IllegalArgumentException("Dependency requires explicit approval: " + coordinate);
      }
      values.add(resolveOne(workspace, coordinate, request.checksum(), offline));
    }
    return List.copyOf(values);
  }

  private ResolvedDependency resolveOne(
      Path workspace, MavenCoordinate coordinate, String expectedChecksum, boolean offline) {
    Path localRepository = localRepository();
    Path sourceJar = localRepository.resolve(coordinate.repositoryPath("jar"));
    if (!Files.isRegularFile(sourceJar)) {
      if (offline)
        throw new IllegalStateException(
            "Dependency is not available in offline cache: " + coordinate);
      fetch(coordinate);
    }
    if (!Files.isRegularFile(sourceJar))
      throw new IllegalStateException("Maven did not resolve " + coordinate);
    String checksum = sha256(sourceJar);
    if (expectedChecksum != null
        && !expectedChecksum.isBlank()
        && !checksum.equalsIgnoreCase(expectedChecksum)) {
      throw new IllegalStateException("Dependency checksum mismatch: " + coordinate);
    }
    Path cache =
        workspace
            .toAbsolutePath()
            .normalize()
            .resolve(".opengis/dependency-cache")
            .resolve(coordinate.repositoryPath("jar"));
    try {
      Files.createDirectories(cache.getParent());
      Files.copy(sourceJar, cache, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot cache dependency: " + coordinate, exception);
    }
    List<String> licenses = licenses(localRepository.resolve(coordinate.repositoryPath("pom")));
    ObjectNode record = new JsonFileStore().objectMapper().createObjectNode();
    record.put("coordinate", coordinate.toString());
    record.put("repository", "https://repo.maven.apache.org/maven2/");
    record.put("sha256", checksum);
    record.putPOJO("licenses", licenses);
    record.put("approved", true);
    record.put("resolved_at", Instant.now().toString());
    new JsonFileStore().write(cache.resolveSibling(cache.getFileName() + ".metadata.json"), record);
    return new ResolvedDependency(coordinate.toString(), cache, checksum, licenses);
  }

  private static Path localRepository() {
    String configured = System.getProperty("maven.repo.local", "");
    return configured.isBlank()
        ? Path.of(System.getProperty("user.home"), ".m2", "repository")
        : Path.of(configured);
  }

  private static void fetch(MavenCoordinate coordinate) {
    String executable =
        System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
            ? "mvn.cmd"
            : "mvn";
    try {
      Process process =
          new ProcessBuilder(
                  executable,
                  "-B",
                  "-ntp",
                  "dependency:get",
                  "-Dtransitive=false",
                  "-Dartifact=" + coordinate)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        destroyTree(process);
        throw new IllegalStateException("Dependency resolution timed out: " + coordinate);
      }
      if (process.exitValue() != 0)
        throw new IllegalStateException("Maven rejected dependency: " + coordinate);
    } catch (IOException exception) {
      throw new IllegalStateException("Maven executable is unavailable", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Dependency resolution was interrupted", exception);
    }
  }

  private static List<String> licenses(Path pom) {
    if (!Files.isRegularFile(pom)) return List.of("UNKNOWN");
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var names = factory.newDocumentBuilder().parse(pom.toFile()).getElementsByTagName("name");
      List<String> result = new ArrayList<>();
      for (int index = 0; index < names.getLength(); index++) {
        var parent = names.item(index).getParentNode();
        if (parent != null && "license".equals(parent.getNodeName()))
          result.add(names.item(index).getTextContent().strip());
      }
      return result.isEmpty() ? List.of("UNKNOWN") : List.copyOf(result);
    } catch (Exception ignored) {
      return List.of("UNKNOWN");
    }
  }

  private static String sha256(Path path) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot checksum dependency " + path, exception);
    }
  }

  private static void destroyTree(Process process) {
    process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  public record Request(String coordinate, boolean approved, String checksum) {}

  public record ResolvedDependency(
      String coordinate, Path jar, String sha256, List<String> licenses) {}
}
