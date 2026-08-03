package org.opengis.gis.operation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.opengis.code.dependency.DependencyResolver;
import org.opengis.code.runner.JavaScriptRunner;
import org.opengis.code.runner.ScriptCallbacks;
import org.opengis.code.runner.ScriptRunRequest;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Operation v2 store, validator, revision ledger and trusted/custom execution router. */
public final class OperationService {
  private static final int MAX_SOURCE_CHARACTERS = 1_000_000;
  private final ObjectMapper mapper;
  private final JsonFileStore files;
  private final OperationRegistry builtins;
  private final JavaScriptRunner scripts;

  public OperationService(ObjectMapper mapper) {
    this.mapper = mapper;
    this.files = new JsonFileStore(mapper);
    this.builtins = new OperationRegistry(mapper);
    this.scripts = new JavaScriptRunner(mapper);
  }

  public ObjectNode list(Path workspace, String query, int limit) {
    String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    LinkedHashMap<String, ObjectNode> values = new LinkedHashMap<>();
    workspaceManifests(workspace).stream()
        .map(this::compatible)
        .filter(value -> matches(value, needle))
        .forEach(value -> values.put(value.path("id").asText(), summary(value)));
    builtins.all().stream()
        .map(operation -> (ObjectNode) operation.manifest())
        .filter(value -> matches(value, needle))
        .forEach(value -> values.putIfAbsent(value.path("id").asText(), summary(value)));
    ObjectNode result = mapper.createObjectNode();
    result.put("success", true);
    result.put("operation_root", root(workspace).toString());
    ArrayNode operations = result.putArray("operations");
    values.values().stream().limit(Math.max(1, Math.min(limit, 200))).forEach(operations::add);
    return result;
  }

  public ObjectNode get(Path workspace, String operationId, boolean includeCode, int maxCodeChars) {
    Location location = locate(workspace, operationId);
    ObjectNode manifest = location.manifest().deepCopy();
    manifest.put("scope", location.scope());
    manifest.put("read_only", location.readOnly());
    manifest.put(
        "path",
        location.directory() == null
            ? "builtin://" + operationId
            : relative(workspace, location.directory()));
    if (location.directory() != null) {
      Path readme = location.directory().resolve("README.md");
      manifest.put("readme", files.readText(readme));
      if (includeCode) {
        Path source = sourcePath(location.directory(), manifest);
        String code = files.readText(source);
        int limit = Math.max(1_000, Math.min(maxCodeChars, MAX_SOURCE_CHARACTERS));
        manifest.put("code", code.substring(0, Math.min(code.length(), limit)));
        manifest.put("code_truncated", code.length() > limit);
      }
    }
    ObjectNode result = mapper.createObjectNode();
    result.put("success", true);
    result.set("operation", manifest);
    return result;
  }

  public ObjectNode create(Path workspace, JsonNode specification, boolean overwrite) {
    String id = safeId(text(specification, "operation_id", text(specification, "id", "")));
    Path directory = root(workspace).resolve(id);
    if (Files.exists(directory) && !overwrite)
      throw new IllegalArgumentException("Operation already exists: " + id);
    String entryClass = text(specification, "entry_class", "workspace.operations." + javaClass(id));
    String source = text(specification, "code", template(entryClass, id));
    if (source.length() > MAX_SOURCE_CHARACTERS)
      throw new IllegalArgumentException("Operation source is too large");
    ObjectNode manifest = manifest(specification, id, entryClass, source, 1);
    writeRevision(directory, manifest, source);
    files.writeText(
        directory.resolve("README.md"),
        "# "
            + manifest.path("name").asText(id)
            + "\n\n"
            + manifest.path("description").asText("Reusable Java operation.")
            + "\n");
    return get(workspace, id, false, 40_000);
  }

  public ObjectNode edit(Path workspace, String operationId, JsonNode changes) {
    Location location = locate(workspace, operationId);
    if (location.readOnly())
      throw new IllegalArgumentException("Built-in operation must be copied before editing");
    if (isLegacy(location.manifest()))
      throw new IllegalArgumentException("Legacy Python operations are read-only in Java mode");
    ObjectNode current = location.manifest().deepCopy();
    String entryClass = current.path("runtime").path("entry_class").asText();
    String source =
        changes.has("code")
            ? changes.path("code").asText()
            : files.readText(sourcePath(location.directory(), current));
    for (String key :
        List.of("name", "description", "status", "input_schema", "output_schema", "permissions")) {
      if (changes.has(key)) current.set(key, changes.get(key).deepCopy());
    }
    if (changes.has("dependencies"))
      current.withObject("runtime").set("dependencies", changes.path("dependencies").deepCopy());
    int revision = current.path("revision").asInt(0) + 1;
    current.put("revision", revision);
    current.put("updated_at", Instant.now().toString());
    current.put("checksum", checksum(source, current));
    writeRevision(location.directory(), current, source);
    if (changes.has("readme"))
      files.writeText(location.directory().resolve("README.md"), changes.path("readme").asText());
    return get(workspace, operationId, false, 40_000);
  }

  public ObjectNode copyToWorkspace(Path workspace, String operationId, boolean overwrite) {
    Location source = locate(workspace, operationId);
    if (!source.readOnly() && !isLegacy(source.manifest()))
      return get(workspace, operationId, false, 40_000);
    String targetId = source.readOnly() ? operationId : operationId + "-java";
    ObjectNode specification = mapper.createObjectNode();
    specification.put("operation_id", targetId);
    specification.put("name", source.manifest().path("name").asText(targetId));
    specification.put("description", "Java workspace copy of " + operationId);
    specification.put("entry_class", "workspace.operations." + javaClass(targetId));
    specification.put("code", template("workspace.operations." + javaClass(targetId), targetId));
    specification.set("input_schema", source.manifest().path("input_schema").deepCopy());
    specification.set("output_schema", source.manifest().path("output_schema").deepCopy());
    specification.putArray("permissions").add("workspace_files");
    create(workspace, specification, overwrite);
    Path directory = root(workspace).resolve(targetId);
    ObjectNode manifest = files.readObject(directory.resolve("operation.json"));
    manifest
        .putObject("provenance")
        .put("copied_from", operationId)
        .put("copied_at", Instant.now().toString());
    files.write(directory.resolve("operation.json"), manifest);
    return get(workspace, targetId, false, 40_000);
  }

  public ObjectNode validate(
      Path workspace, String operationId, JsonNode parameters, boolean offline) {
    Location location = locate(workspace, operationId);
    if (isLegacy(location.manifest()))
      return legacyValidation(operationId, location.manifest(), location.directory());
    List<ObjectNode> contractErrors =
        validateParameters(location.manifest().path("input_schema"), parameters);
    if (location.scope().equals("builtin")) {
      ObjectNode result = mapper.createObjectNode();
      result.put("success", true);
      result.put("ok", contractErrors.isEmpty());
      result.put("operation_id", operationId);
      result.put("runtime", "builtin-java");
      result.putPOJO("errors", contractErrors);
      result.putArray("warnings");
      return result;
    }
    String source = files.readText(sourcePath(location.directory(), location.manifest()));
    var code =
        scripts.validateAndCompile(
            workspace,
            source,
            location.manifest().path("runtime").path("entry_class").asText(),
            strings(location.manifest().path("permissions")),
            dependencyRequests(location.manifest().path("runtime").path("dependencies")),
            offline);
    ObjectNode result = mapper.createObjectNode();
    result.put("success", true);
    result.put("ok", contractErrors.isEmpty() && code.ok());
    result.put("operation_id", operationId);
    ArrayNode errors = result.putArray("errors");
    contractErrors.forEach(errors::add);
    code.sourceValidation()
        .errors()
        .forEach(
            value -> errors.addObject().put("code", value.code()).put("message", value.message()));
    code.compilerDiagnostics()
        .forEach(
            value -> errors.addObject().put("code", "compiler_diagnostic").put("message", value));
    result.set("warnings", mapper.valueToTree(code.sourceValidation().warnings()));
    result.putPOJO("resolved_dependencies", code.resolvedDependencies());
    result.put("revision", location.manifest().path("revision").asInt());
    result.put("checksum", location.manifest().path("checksum").asText());
    return result;
  }

  public ObjectNode run(
      Path workspace,
      String operationId,
      JsonNode parameters,
      Duration timeout,
      boolean offline,
      CancellationToken cancellation,
      ScriptCallbacks callbacks) {
    Location location = locate(workspace, operationId);
    if (isLegacy(location.manifest()))
      throw new IllegalArgumentException("legacy-python operations cannot execute in Java mode");
    ObjectNode validation = validate(workspace, operationId, parameters, offline);
    if (!validation.path("ok").asBoolean())
      throw new IllegalArgumentException(
          "Operation validation failed: " + validation.path("errors"));
    String runId = "oprun-" + UUID.randomUUID();
    Instant started = Instant.now();
    JsonNode output;
    String status;
    if (location.scope().equals("builtin")) {
      output = builtins.find(operationId).orElseThrow().run(workspace, parameters, cancellation);
      status = output.path("success").asBoolean(true) ? "success" : "failed";
    } else {
      String source = files.readText(sourcePath(location.directory(), location.manifest()));
      @SuppressWarnings("unchecked")
      Map<String, Object> parameterMap = mapper.convertValue(parameters, Map.class);
      var result =
          scripts.run(
              new ScriptRunRequest(
                  workspace,
                  runId,
                  operationId,
                  location.manifest().path("runtime").path("entry_class").asText(),
                  source,
                  parameterMap,
                  strings(location.manifest().path("permissions")),
                  dependencyRequests(location.manifest().path("runtime").path("dependencies")),
                  offline,
                  timeout,
                  256,
                  cancellation),
              callbacks);
      output = mapper.valueToTree(result.output());
      status = "completed".equals(result.status()) ? "success" : result.status();
    }
    ObjectNode record = mapper.createObjectNode();
    record.put("schema_version", "2.0");
    record.put("run_id", runId);
    record.put("operation_id", operationId);
    record.put("status", status);
    record.put("scope", location.scope());
    record.put("revision", location.manifest().path("revision").asInt());
    record.put(
        "checksum", location.manifest().path("checksum").asText("builtin:" + operationId + ":1"));
    record.put("started_at", started.toString());
    record.put("finished_at", Instant.now().toString());
    record.set("params", parameters.deepCopy());
    record.set("output", output);
    Path run =
        new WorkspaceLayout(workspace)
            .resolve("operation-runs")
            .resolve(operationId)
            .resolve(runId)
            .resolve("run.json");
    files.write(run, record);
    files.append(run.getParent().getParent().resolve("index.jsonl"), record);
    return record;
  }

  public ObjectNode promoteScript(
      Path workspace, String scriptPath, String operationId, boolean overwrite) {
    Path scriptRoot = new WorkspaceLayout(workspace).resolve("scripts");
    Path source = scriptRoot.resolve(scriptPath).normalize();
    if (!source.startsWith(scriptRoot)
        || !Files.isRegularFile(source)
        || !source.toString().endsWith(".java")) {
      throw new IllegalArgumentException("Only archived Java scripts can be promoted");
    }
    Path metadataPath =
        source.resolveSibling(source.getFileName().toString().replace(".java", ".metadata.json"));
    if (!Files.isRegularFile(metadataPath))
      throw new IllegalArgumentException("Script metadata is required");
    ObjectNode metadata = files.readObject(metadataPath);
    String runId = metadata.path("run_id").asText();
    Path runRecord =
        new WorkspaceLayout(workspace).resolve("script-runs").resolve(runId).resolve("run.json");
    if (!Files.isRegularFile(runRecord)
        || !"completed".equals(files.readObject(runRecord).path("status").asText())) {
      throw new IllegalArgumentException(
          "Only a successfully executed Java script can be promoted");
    }
    ObjectNode specification = mapper.createObjectNode();
    specification.put(
        "operation_id",
        operationId == null || operationId.isBlank()
            ? safeId(source.getFileName().toString().replace(".java", ""))
            : safeId(operationId));
    specification.put("name", metadata.path("semantic_name").asText("Promoted Java operation"));
    specification.put("description", "Promoted from a verified Java Script run");
    specification.put("entry_class", metadata.path("entry_class").asText());
    specification.put("code", files.readText(source));
    specification.putArray("permissions").add("workspace_files");
    specification
        .putObject("provenance")
        .put("source_run_id", runId)
        .put("source_sha256", metadata.path("sha256").asText());
    return create(workspace, specification, overwrite);
  }

  public ObjectNode legacyReport(Path workspace, String operationId) {
    Location location = locate(workspace, operationId);
    if (!isLegacy(location.manifest()))
      throw new IllegalArgumentException("Operation is already Java v2");
    return legacyValidation(operationId, location.manifest(), location.directory());
  }

  private ObjectNode manifest(
      JsonNode specification, String id, String entryClass, String source, int revision) {
    Instant now = Instant.now();
    ObjectNode value = mapper.createObjectNode();
    value.put("schema_version", "2.0");
    value.put("api_version", "1.0");
    value.put("id", id);
    value.put("name", text(specification, "name", id));
    value.put("version", text(specification, "version", "0.1.0"));
    value.put("revision", revision);
    value.put("status", "draft");
    value.put("description", text(specification, "description", ""));
    value.put("entry", "src/main/java/" + entryClass.replace('.', '/') + ".java");
    ObjectNode runtime = value.putObject("runtime");
    runtime.put("language", "java");
    runtime.put("entry_class", entryClass);
    runtime.put("jdk", ">=21");
    runtime.set(
        "dependencies",
        specification.path("dependencies").isArray()
            ? specification.path("dependencies").deepCopy()
            : mapper.createArrayNode());
    value.set(
        "permissions",
        specification.path("permissions").isArray()
            ? specification.path("permissions").deepCopy()
            : mapper.valueToTree(List.of("workspace_files")));
    value.set(
        "input_schema",
        specification.path("input_schema").isObject()
            ? specification.path("input_schema").deepCopy()
            : mapper.valueToTree(
                Map.of("type", "object", "properties", Map.of(), "required", List.of())));
    value.set(
        "output_schema",
        specification.path("output_schema").isObject()
            ? specification.path("output_schema").deepCopy()
            : mapper.valueToTree(Map.of("type", "object")));
    value.set(
        "provenance",
        specification.path("provenance").isObject()
            ? specification.path("provenance").deepCopy()
            : mapper.createObjectNode());
    value.put("created_at", now.toString());
    value.put("updated_at", now.toString());
    value.put("checksum", checksum(source, value));
    return value;
  }

  private void writeRevision(Path directory, ObjectNode manifest, String source) {
    Path sourcePath = sourcePath(directory, manifest);
    files.writeText(sourcePath, source.stripTrailing() + "\n");
    files.write(directory.resolve("operation.json"), manifest);
    int revision = manifest.path("revision").asInt();
    Path snapshot = directory.resolve("revisions").resolve(String.format("%06d", revision));
    files.writeText(snapshot.resolve("source.java"), source.stripTrailing() + "\n");
    files.write(snapshot.resolve("manifest.json"), manifest);
  }

  private Location locate(Path workspace, String rawId) {
    String id = safeId(rawId);
    Path directory = root(workspace).resolve(id).normalize();
    if (Files.isRegularFile(directory.resolve("operation.json"))) {
      return new Location(
          "workspace",
          directory,
          compatible(files.readObject(directory.resolve("operation.json"))),
          false);
    }
    Optional<BuiltinOperation> builtin = builtins.find(id);
    if (builtin.isPresent())
      return new Location("builtin", null, (ObjectNode) builtin.get().manifest(), true);
    throw new IllegalArgumentException("Operation not found: " + id);
  }

  private List<ObjectNode> workspaceManifests(Path workspace) {
    Path root = root(workspace);
    if (!Files.isDirectory(root)) return List.of();
    List<ObjectNode> values = new ArrayList<>();
    try (var directories = Files.list(root)) {
      directories
          .filter(Files::isDirectory)
          .map(path -> path.resolve("operation.json"))
          .filter(Files::isRegularFile)
          .sorted()
          .forEach(path -> values.add(files.readObject(path)));
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot list workspace operations", exception);
    }
    return values;
  }

  private ObjectNode compatible(ObjectNode source) {
    ObjectNode value = source.deepCopy();
    String language =
        value
            .path("runtime")
            .path("language")
            .asText(value.path("entry").asText().endsWith(".py") ? "python" : "java");
    if (!"java".equalsIgnoreCase(language)
        || value.path("schema_version").asText("1.0").startsWith("1")) {
      value.put("compatibility_status", "legacy-python");
      value.put("read_only", true);
    } else {
      value.put("compatibility_status", "java-v2");
    }
    value.put("scope", "workspace");
    return value;
  }

  private ObjectNode legacyValidation(String id, ObjectNode manifest, Path directory) {
    ObjectNode result = mapper.createObjectNode();
    result.put("success", true);
    result.put("ok", false);
    result.put("operation_id", id);
    result.put("status", "manual_migration_required");
    ArrayNode issues = result.putArray("errors");
    issues
        .addObject()
        .put("code", "legacy_python_runtime")
        .put("message", "Python v1 operations are never executed by Java mode");
    result.putPOJO("python_dependencies", manifest.path("runtime").path("dependencies"));
    result.putPOJO("source_files", directory == null ? List.of() : listRelative(directory));
    result.put("java_entry_class", "workspace.operations." + javaClass(id));
    result.put("java_template", template("workspace.operations." + javaClass(id), id));
    result.putPOJO(
        "migration_steps",
        List.of(
            "Review Python dependencies and GIS semantics",
            "Port main.py to OpenGisScript",
            "Validate Java v2 manifest",
            "Run Python/Java fixture comparison",
            "Choose converted or archived_readonly"));
    return result;
  }

  private List<ObjectNode> validateParameters(JsonNode schema, JsonNode parameters) {
    List<ObjectNode> errors = new ArrayList<>();
    if (parameters != null && !parameters.isObject()) {
      errors.add(
          mapper
              .createObjectNode()
              .put("code", "params_not_object")
              .put("message", "Operation parameters must be an object"));
      return errors;
    }
    for (JsonNode required : schema.path("required")) {
      if (parameters == null || !parameters.has(required.asText())) {
        errors.add(
            mapper
                .createObjectNode()
                .put("code", "missing_required_param")
                .put("message", "Missing required parameter: " + required.asText()));
      }
    }
    return errors;
  }

  private List<DependencyResolver.Request> dependencyRequests(JsonNode values) {
    List<DependencyResolver.Request> result = new ArrayList<>();
    for (JsonNode value : values) {
      if (value.isTextual()) result.add(new DependencyResolver.Request(value.asText(), false, ""));
      else
        result.add(
            new DependencyResolver.Request(
                value.path("coordinate").asText(),
                value.path("approved").asBoolean(false),
                value.path("checksum").asText("")));
    }
    return result;
  }

  private static Set<String> strings(JsonNode values) {
    java.util.HashSet<String> result = new java.util.HashSet<>();
    for (JsonNode value : values) result.add(value.asText());
    return Set.copyOf(result);
  }

  private ObjectNode summary(ObjectNode value) {
    ObjectNode result = mapper.createObjectNode();
    for (String key :
        List.of(
            "id",
            "name",
            "version",
            "revision",
            "status",
            "scope",
            "read_only",
            "description",
            "compatibility_status",
            "updated_at",
            "checksum")) {
      if (value.has(key)) result.set(key, value.get(key).deepCopy());
    }
    result.put("runtime_language", value.path("runtime").path("language").asText("java"));
    result.put("entry_class", value.path("runtime").path("entry_class").asText(""));
    return result;
  }

  private static boolean matches(ObjectNode value, String needle) {
    return needle.isBlank() || value.toString().toLowerCase(Locale.ROOT).contains(needle);
  }

  private static boolean isLegacy(JsonNode manifest) {
    return "legacy-python".equals(manifest.path("compatibility_status").asText())
        || "python".equalsIgnoreCase(manifest.path("runtime").path("language").asText());
  }

  private static Path sourcePath(Path directory, JsonNode manifest) {
    Path path = directory.resolve(manifest.path("entry").asText()).normalize();
    if (!path.startsWith(directory))
      throw new IllegalArgumentException("Operation entry escapes its directory");
    return path;
  }

  private static Path root(Path workspace) {
    return new WorkspaceLayout(workspace).resolve("operations");
  }

  private static String safeId(String value) {
    String id =
        value == null
            ? ""
            : value
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
    if (id.isBlank() || id.length() > 80)
      throw new IllegalArgumentException("Invalid operation id");
    return id;
  }

  private static String javaClass(String id) {
    StringBuilder value = new StringBuilder("Operation");
    boolean upper = true;
    for (char character : id.toCharArray()) {
      if (!Character.isLetterOrDigit(character)) upper = true;
      else {
        value.append(upper ? Character.toUpperCase(character) : character);
        upper = false;
      }
    }
    return value.toString();
  }

  private static String template(String entryClass, String id) {
    int dot = entryClass.lastIndexOf('.');
    String packageName = dot < 0 ? "" : "package " + entryClass.substring(0, dot) + ";\n\n";
    String className = dot < 0 ? entryClass : entryClass.substring(dot + 1);
    return packageName
        + "import java.util.Map;\n"
        + "import org.opengis.script.sdk.OpenGisScript;\n"
        + "import org.opengis.script.sdk.ScriptContext;\n\n"
        + "public final class "
        + className
        + " implements OpenGisScript {\n"
        + "  @Override public Object run(ScriptContext context, Map<String,Object> params) {\n"
        + "    context.progress().emit(1.0, \""
        + id
        + " completed\");\n"
        + "    return Map.of(\"success\", true, \"operation_id\", \""
        + id
        + "\");\n"
        + "  }\n}\n";
  }

  private static String checksum(String source, JsonNode manifest) {
    try {
      String normalized =
          source.replace("\r\n", "\n")
              + "\n"
              + manifest.path("id").asText()
              + "\n"
              + manifest.path("revision").asInt();
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(normalized.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String text(JsonNode value, String key, String fallback) {
    String result = value.path(key).asText(fallback);
    return result == null ? fallback : result;
  }

  private static String relative(Path workspace, Path path) {
    return workspace
        .toAbsolutePath()
        .normalize()
        .relativize(path.toAbsolutePath().normalize())
        .toString()
        .replace('\\', '/');
  }

  private static List<String> listRelative(Path directory) {
    try (var paths = Files.walk(directory)) {
      return paths
          .filter(Files::isRegularFile)
          .sorted(Comparator.naturalOrder())
          .map(path -> directory.relativize(path).toString().replace('\\', '/'))
          .toList();
    } catch (IOException exception) {
      return List.of();
    }
  }

  private record Location(String scope, Path directory, ObjectNode manifest, boolean readOnly) {}
}
