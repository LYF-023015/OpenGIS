package org.opengis.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opengis.platform.persistence.JsonFileStore;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Repeatable read-only audit and Java template generator for user Python Workers. */
public final class WorkerMigrationService {
  private static final Pattern IMPORT =
      Pattern.compile("(?m)^\\s*(?:from|import)\\s+([A-Za-z0-9_.-]+)");
  private static final Pattern ENVIRONMENT =
      Pattern.compile("(?:os\\.environ(?:\\.get)?|os\\.getenv)\\s*[\\[(]\\s*['\"]([^'\"]+)");
  private final ObjectMapper mapper;
  private final JsonFileStore files;

  public WorkerMigrationService(ObjectMapper mapper) {
    this.mapper = mapper;
    this.files = new JsonFileStore(mapper);
  }

  public ObjectNode inspect(Path workspace, String workerId) {
    Path folder = find(workspace, workerId);
    ObjectNode metadata =
        Files.exists(folder.resolve("metadata.json"))
            ? files.readObject(folder.resolve("metadata.json"))
            : mapper.createObjectNode();
    ObjectNode manifest =
        Files.exists(folder.resolve("manifest.json"))
            ? files.readObject(folder.resolve("manifest.json"))
            : mapper.createObjectNode();
    List<Path> python = sourceFiles(folder);
    Set<String> imports = new LinkedHashSet<>();
    Set<String> environment = new LinkedHashSet<>();
    boolean dynamicMap = false;
    boolean network = false;
    boolean toolOrRpc = false;
    for (Path path : python) {
      String source = files.readText(path);
      Matcher imported = IMPORT.matcher(source);
      while (imported.find()) imports.add(imported.group(1));
      Matcher env = ENVIRONMENT.matcher(source);
      while (env.find()) environment.add(env.group(1));
      dynamicMap |= source.contains("dynamic_layer") || source.contains("emit_dynamic_");
      toolOrRpc |=
          source.contains("rpc.")
              || source.contains("tool_call")
              || source.contains("opengis_method");
      network |=
          source.contains("requests")
              || source.contains("httpx")
              || source.contains("urllib")
              || source.contains("socket");
    }
    ObjectNode report = mapper.createObjectNode();
    report.put("schema_version", "2.0");
    report.put("worker_id", workerId);
    report.put("status", python.isEmpty() ? "java_or_unknown" : "manual_migration_required");
    report.put(
        "source_folder",
        workspace.toAbsolutePath().normalize().relativize(folder).toString().replace('\\', '/'));
    report.putPOJO(
        "source_files",
        python.stream()
            .map(path -> folder.relativize(path).toString().replace('\\', '/'))
            .toList());
    report.putPOJO("python_imports", imports);
    report.putPOJO("environment_variables", environment);
    report.put("uses_network", network);
    report.put("uses_tool_or_rpc", toolOrRpc);
    report.put("uses_dynamic_map", dynamicMap);
    report.set("legacy_manifest", manifest);
    report.set("legacy_metadata", metadata);
    ArrayNode permissions = report.putArray("suggested_permissions");
    permissions.add("workspace_files");
    if (network) permissions.add("network");
    report.putPOJO(
        "migration_steps",
        List.of(
            "Port datasource I/O to a Java service class",
            "Port transformations into a separately testable service class",
            "Use WorkerContext.dynamicMap() for full/diff events",
            "Validate dependencies, permissions and fixture output",
            "Mark the asset converted, archived_readonly or discarded_confirmed"));
    report.put("java_template", template(workerId, dynamicMap));
    Path destination =
        workspace.resolve(".opengis/migration/workers").resolve(workerId + ".json").normalize();
    files.write(destination, report);
    report.put(
        "report_path",
        workspace
            .toAbsolutePath()
            .normalize()
            .relativize(destination)
            .toString()
            .replace('\\', '/'));
    return report;
  }

  public ObjectNode inspectAll(Path workspace) {
    ObjectNode result = mapper.createObjectNode();
    result.put("success", true);
    ArrayNode reports = result.putArray("reports");
    Path root = workspace.toAbsolutePath().normalize().resolve("worker");
    if (!Files.isDirectory(root)) return result;
    try (var folders = Files.list(root)) {
      folders
          .filter(Files::isDirectory)
          .sorted()
          .forEach(
              folder -> {
                String id = workerId(folder);
                if (!sourceFiles(folder).isEmpty()) reports.add(inspect(workspace, id));
              });
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot scan Python Workers", exception);
    }
    return result;
  }

  private Path find(Path workspace, String workerId) {
    Path root = workspace.toAbsolutePath().normalize().resolve("worker");
    if (!Files.isDirectory(root))
      throw new IllegalArgumentException("Worker not found: " + workerId);
    try (var folders = Files.list(root)) {
      return folders
          .filter(Files::isDirectory)
          .filter(folder -> workerId(folder).equals(workerId))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot inspect Worker", exception);
    }
  }

  private String workerId(Path folder) {
    Path metadata = folder.resolve("metadata.json");
    if (Files.isRegularFile(metadata)) {
      String id = files.readObject(metadata).path("worker_id").asString();
      if (!id.isBlank()) return id;
    }
    return folder.getFileName().toString();
  }

  private static List<Path> sourceFiles(Path folder) {
    if (!Files.isDirectory(folder)) return List.of();
    try (var paths = Files.walk(folder)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".py"))
          .sorted(Comparator.naturalOrder())
          .toList();
    } catch (IOException exception) {
      return List.of();
    }
  }

  private static String template(String workerId, boolean dynamicMap) {
    String publish =
        dynamicMap
            ? "context.dynamicMap().full(\"live\", \"Live Layer\", Map.of(\"type\", \"FeatureCollection\", \"features\", List.of()), Map.of(), sequence++);"
            : "context.progress().emit(0.0, \"heartbeat \" + sequence++);";
    return """
        package workspace.workers;

        import java.time.Duration;
        import java.util.List;
        import java.util.Map;
        import org.opengis.script.sdk.OpenGisWorker;
        import org.opengis.script.sdk.WorkerContext;

        public final class MigratedWorker implements OpenGisWorker {
          private volatile boolean stopped;
          public void start(WorkerContext context) throws Exception {
            long sequence = 0;
            while (!stopped) {
              context.checkCancelled();
              %s
              context.sleep(Duration.ofSeconds(5));
            }
          }
          public void stop() { stopped = true; }
          public Map<String,Object> health() {
            return Map.of("status", stopped ? "stopped" : "running", "legacy_worker_id", "%s");
          }
        }
        """
        .formatted(publish, workerId);
  }
}
