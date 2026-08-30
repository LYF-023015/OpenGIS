package org.opengis.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.agent.persistence.AgentProfileStore;
import org.opengis.agent.persistence.ArtifactStore;
import org.opengis.agent.persistence.ContextStore;
import org.opengis.agent.persistence.ConversationTitleStore;
import org.opengis.agent.persistence.PermissionRuleStore;
import org.opengis.agent.persistence.RunArchive;
import org.opengis.agent.persistence.ScriptArchive;
import org.opengis.agent.persistence.SessionStore;
import org.opengis.knowledge.persistence.MemoryStore;
import org.opengis.platform.electron.ElectronDataStore;
import org.opengis.platform.git.GitNotAvailableException;
import org.opengis.platform.git.GitWorkspaceAdapter;
import org.opengis.platform.migration.MigrationInspection;
import org.opengis.platform.migration.WorkspaceMigrationService;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceCompatibilityReader;
import org.opengis.platform.persistence.WorkspaceCompatibilityReport;
import org.opengis.worker.persistence.WorkerMetadataStore;
import org.opengis.workflow.persistence.WorkflowStore;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WorkspacePersistenceIT {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @Test
  void readsEveryPersistentFamilyFromThePythonPhaseZeroFixture() {
    Path workspace = phaseZeroWorkspace();
    WorkspaceCompatibilityReport report = new WorkspaceCompatibilityReader().inspect(workspace);

    assertThat(report.compatible()).isTrue();
    assertThat(report.persistentStoreCount()).isEqualTo(15);
    assertThat(report.readableStoreCount()).isEqualTo(16);
    assertThat(report.stores()).allMatch(store -> store.readable());

    assertThat(new SessionStore(workspace).load().path("sessions")).hasSize(1);
    assertThat(new AgentProfileStore(workspace).list()).hasSize(5);
    assertThat(new PermissionRuleStore(workspace).list()).hasSize(1);
    assertThat(new ArtifactStore(workspace).list()).hasSize(1);
    assertThat(new ConversationTitleStore(workspace).load())
        .containsExactly("conversation-phase0-001");
    assertThat(new ContextStore(workspace).load("conversation-phase0-001")).isPresent();
    assertThat(new MemoryStore(workspace).list()).hasSize(4);
    assertThat(new MemoryStore(workspace).readLegacyMarkdown()).contains("Synthetic");
    assertThat(new WorkflowStore(workspace).load("workflow-phase0")).isPresent();
    assertThat(new WorkflowStore(workspace).loadStepOutput(1, "node-a")).contains("Synthetic");
    assertThat(RunArchive.load(workspace, "run-phase0-001")).isPresent();
    assertThat(RunArchive.list(workspace)).extracting("runId").contains("run-phase0-001");
  }

  @Test
  void JavaWritersRoundTripEveryPersistentFamily() throws Exception {
    Path workspace = temporaryDirectory.resolve("java-written-workspace");
    Files.createDirectories(workspace);

    SessionStore sessions = new SessionStore(workspace);
    sessions.putSession("java-session", session("java-session"));
    sessions.putInboxItem("java-inbox", inbox("java-inbox"));

    ObjectNode profile = objectMapper.createObjectNode();
    profile.put("name", "java-profile");
    profile.put("mode", "build");
    profile.put("description", "Java profile");
    profile.put("permission_level", "safe_write");
    profile.putArray("tool_groups").add("core");
    profile.putObject("metadata");
    new AgentProfileStore(workspace).save(List.of(profile));

    ObjectNode rule = objectMapper.createObjectNode();
    rule.put("id", "java-rule");
    rule.put("tool", "read_file");
    rule.put("action", "allow");
    rule.put("scope", "workspace");
    rule.put("reason", "interop");
    new PermissionRuleStore(workspace).save(List.of(rule));

    ObjectNode artifact = objectMapper.createObjectNode();
    artifact.put("id", "java-artifact");
    artifact.put("run_id", "java-run");
    artifact.put("type", "geojson");
    artifact.put("path", "output/java.geojson");
    new ArtifactStore(workspace).append(artifact);
    new ConversationTitleStore(workspace).save(Set.of("java-conversation"));

    ObjectNode context = objectMapper.createObjectNode();
    context.put("schema_version", "1.0");
    context.put("conversation_id", "java-conversation");
    context.putArray("messages");
    context.putObject("working_state");
    new ContextStore(workspace).save("java-conversation", context);

    ObjectNode memory = objectMapper.createObjectNode();
    memory.put("id", "java-memory");
    memory.put("kind", "fact");
    memory.put("scope", "project");
    memory.put("title", "Java memory");
    memory.put("content", "Java writer can be read by Python.");
    memory.putArray("tags").add("phase3");
    memory.put("confidence", 1.0);
    memory.put("created_at", 1767225600.0);
    memory.put("last_used_at", 0.0);
    memory.putObject("metadata");
    new MemoryStore(workspace).append(memory);
    new MemoryStore(workspace).writeLegacyMarkdown("# Java legacy memory\n");

    ObjectNode workflow = objectMapper.createObjectNode();
    workflow.put("schema_version", "1.0");
    workflow.put("id", "java-workflow");
    workflow.put("name", "Java workflow");
    workflow.putArray("nodes").addObject().put("id", "node-a").put("task", "Read Java fixture");
    workflow.putArray("edges");
    new WorkflowStore(workspace).save("java-workflow", workflow);
    new WorkflowStore(workspace).saveStepOutput(1, "node-a", "# Java step output\n");

    ObjectNode worker = objectMapper.createObjectNode();
    worker.put("id", "java-worker");
    worker.put("name", "Java worker metadata");
    worker.put("status", "paused");
    worker.put("workspace_path", workspace.toString());
    worker.put("created_at", 1767225600.0);
    worker.put("updated_at", 1767225601.0);
    new WorkerMetadataStore(workspace).save("java-worker", worker);
    assertThat(new WorkerMetadataStore(workspace).list()).hasSize(1);

    ScriptArchive scripts = new ScriptArchive(workspace, "java-run");
    assertThat(scripts.writeStep(1, "phase3 interop", "print('ok')", Map.of())).exists();

    RunArchive run = RunArchive.open(workspace, "java-run", "interop", "test-model", null);
    ObjectNode step = objectMapper.createObjectNode();
    step.put("step", 1);
    step.put("code", "print('ok')");
    run.appendStep(step);
    ObjectNode call = objectMapper.createObjectNode();
    call.put("call_id", "call-1");
    call.put("name", "read_file");
    call.put("status", "running");
    call.putObject("arguments");
    call.putObject("metadata");
    run.appendToolCall(call);
    ObjectNode part = objectMapper.createObjectNode();
    part.put("id", "part-1");
    part.put("type", "tool");
    part.put("status", "streaming");
    part.putObject("data");
    run.appendMessagePart(part);
    run.close("error", "partial answer", "synthetic failure");

    RunArchive loaded = RunArchive.load(workspace, "java-run").orElseThrow();
    assertThat(loaded.meta().path("status").asString()).isEqualTo("error");
    assertThat(loaded.read("tool_calls.jsonl").getLast().path("status").asString())
        .isEqualTo("error");
    assertThat(loaded.read("message_parts.jsonl").getLast().path("status").asString())
        .isEqualTo("failed");
  }

  @Test
  void upgradesLegacyElectronDataWithoutDroppingUnknownFields() {
    Path userData = temporaryDirectory.resolve("electron-user-data");
    JsonFileStore files = new JsonFileStore(objectMapper);
    ObjectNode legacySettings = objectMapper.createObjectNode();
    legacySettings.putObject("appearance").put("theme", "light");
    legacySettings.putObject("customPlugin").put("enabled", true);
    files.write(userData.resolve("settings.json"), legacySettings);
    ObjectNode legacyProjects = objectMapper.createObjectNode();
    legacyProjects.putArray("projects").addObject().put("id", "project-1").put("name", "Legacy");
    legacyProjects.put("lastProjectId", "project-1");
    files.write(userData.resolve("projects.json"), legacyProjects);

    ElectronDataStore electron = new ElectronDataStore(userData, files);
    ObjectNode settings = electron.loadSettings();
    ObjectNode projects = electron.loadProjects();

    assertThat(settings.path("appearance").path("theme").asString()).isEqualTo("light");
    assertThat(settings.path("appearance").path("language").asString()).isEqualTo("en");
    assertThat(settings.path("customPlugin").path("enabled").asBoolean()).isTrue();
    assertThat(settings.path("backend").path("preferredRuntime").asString()).isEqualTo("java");
    assertThat(settings.path("backend").path("fallbackRuntime").asString()).isEqualTo("python");
    assertThat(settings.path("schemaVersion").asInt()).isEqualTo(2);
    assertThat(projects.path("projects")).hasSize(1);
    assertThat(projects.path("lastProjectId").asString()).isEqualTo("project-1");
    assertThat(projects.path("backend").path("javaCompatible").asBoolean()).isTrue();
  }

  @Test
  void migrationIsInspectableIdempotentAndRollbackKeepsOriginalStores() throws Exception {
    Path workspace = temporaryDirectory.resolve("migration-workspace");
    copyTree(phaseZeroWorkspace(), workspace);
    byte[] sessionsBefore = Files.readAllBytes(workspace.resolve(".opengis/sessions.json"));
    WorkspaceMigrationService migrations = new WorkspaceMigrationService();

    MigrationInspection before = migrations.inspect(workspace);
    assertThat(before.applicable()).isTrue();
    ObjectNode applied = migrations.apply(workspace);
    ObjectNode reapplied = migrations.apply(workspace);

    assertThat(applied.path("state").asString()).isEqualTo("applied");
    assertThat(reapplied.path("migration_id").asString())
        .isEqualTo(applied.path("migration_id").asString());
    assertThat(migrations.inspect(workspace).alreadyApplied()).isTrue();
    assertThat(applied.path("entries")).isNotEmpty();
    assertThat(Files.readAllBytes(workspace.resolve(".opengis/sessions.json")))
        .containsExactly(sessionsBefore);

    ObjectNode rolledBack = migrations.rollback(workspace);
    assertThat(rolledBack.path("state").asString()).isEqualTo("rolled_back");
    assertThat(workspace.resolve(".opengis/java-backend.json")).doesNotExist();
    assertThat(workspace.resolve(".opengis/migrations/manifest.json")).exists();
    assertThat(Files.readAllBytes(workspace.resolve(".opengis/sessions.json")))
        .containsExactly(sessionsBefore);
  }

  @Test
  void migrationAcceptsAValidSparseWorkspace() throws Exception {
    Path workspace = temporaryDirectory.resolve("sparse-workspace");
    Files.createDirectories(workspace.resolve(".opengis"));
    WorkspaceMigrationService migrations = new WorkspaceMigrationService();

    assertThat(migrations.inspect(workspace).applicable()).isTrue();
    assertThat(migrations.apply(workspace).path("state").asString()).isEqualTo("applied");
    assertThat(workspace.resolve(".opengis/java-backend.json")).exists();
  }

  @Test
  void gitAdapterCreatesSnapshotsRevertsAndReportsMissingGit() throws Exception {
    Path workspace = temporaryDirectory.resolve("git-workspace");
    Files.createDirectories(workspace);
    runProcess(workspace, "git", "init", "--quiet");
    Files.writeString(workspace.resolve("value.txt"), "one", StandardCharsets.UTF_8);
    GitWorkspaceAdapter git = new GitWorkspaceAdapter();
    String first = git.snapshot(workspace, "run-one", "before");
    Files.writeString(workspace.resolve("value.txt"), "two", StandardCharsets.UTF_8);
    String second = git.snapshot(workspace, "run-two", "after");

    assertThat(first).isNotEqualTo(second);
    assertThat(git.revertHard(workspace, first)).isEqualTo(first);
    assertThat(Files.readString(workspace.resolve("value.txt"), StandardCharsets.UTF_8))
        .isEqualTo("one");
    assertThatThrownBy(() -> new GitWorkspaceAdapter("definitely-missing-git-phase3").version())
        .isInstanceOf(GitNotAvailableException.class)
        .hasMessageContaining("not available");
  }

  private ObjectNode session(String id) {
    ObjectNode value = objectMapper.createObjectNode();
    value.put("id", id);
    value.put("kind", "chat");
    value.put("profile_name", "java-profile");
    value.putNull("parent_id");
    value.put("run_id", "java-run");
    value.put("title", "Java interop");
    value.put("status", "success");
    value.put("created_at", 1767225600.0);
    value.put("updated_at", 1767225601.0);
    value.putArray("children");
    value.put("summary", "done");
    value.putObject("metadata");
    return value;
  }

  private ObjectNode inbox(String id) {
    ObjectNode value = objectMapper.createObjectNode();
    value.put("id", id);
    value.put("prompt", "Java interop");
    value.put("conversation_id", "java-conversation");
    value.put("profile_name", "java-profile");
    value.put("session_id", "java-session");
    value.put("run_id", "java-run");
    value.put("status", "success");
    value.put("error", "");
    value.put("created_at", 1767225600.0);
    value.put("updated_at", 1767225601.0);
    value.putObject("metadata");
    return value;
  }

  private static ProcessResult runProcess(Path directory, String... command) throws Exception {
    Process process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException("Process timed out: " + String.join(" ", command));
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(process.exitValue(), output);
  }

  private static void copyTree(Path source, Path target) throws IOException {
    try (var paths = Files.walk(source)) {
      for (Path path : paths.toList()) {
        Path destination = target.resolve(source.relativize(path));
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static Path phaseZeroWorkspace() {
    return repositoryRoot().resolve("test/phase0/fixtures/opengis-workspace");
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.exists(candidate.resolve("package.json"))
          && Files.exists(candidate.resolve("java-backend/pom.xml"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Cannot locate OpenGIS repository root");
  }

  private record ProcessResult(int exitCode, String output) {}
}
