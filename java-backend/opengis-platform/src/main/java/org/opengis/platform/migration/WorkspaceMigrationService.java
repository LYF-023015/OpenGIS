package org.opengis.platform.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.StoreInspection;
import org.opengis.platform.persistence.WorkspaceCompatibilityReader;
import org.opengis.platform.persistence.WorkspaceCompatibilityReport;
import org.opengis.platform.persistence.WorkspaceLayout;
import org.opengis.platform.persistence.WorkspaceStoreException;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Idempotent inspect/apply/rollback lifecycle for the Java-compatible workspace marker. */
public class WorkspaceMigrationService {
  public static final String TARGET_VERSION = "3.0-java";

  private final JsonFileStore files = new JsonFileStore();
  private final WorkspaceCompatibilityReader compatibilityReader =
      new WorkspaceCompatibilityReader(files);

  public MigrationInspection inspect(Path workspaceRoot) {
    WorkspaceLayout layout = new WorkspaceLayout(workspaceRoot);
    WorkspaceCompatibilityReport compatibility = compatibilityReader.inspect(workspaceRoot);
    Path markerPath = layout.resolve("java-backend.json");
    boolean applied = Files.exists(markerPath);
    String current =
        applied
            ? files.readObject(markerPath).path("storage_version").asText("unknown")
            : "python-legacy";
    List<String> issues =
        compatibility.stores().stream()
            .filter(StoreInspection::required)
            .filter(store -> !store.readable())
            .map(store -> store.name() + ": " + store.detail())
            .toList();
    return new MigrationInspection(
        compatibility.compatible() && !applied,
        applied,
        current,
        TARGET_VERSION,
        compatibility,
        issues);
  }

  public synchronized ObjectNode apply(Path workspaceRoot) {
    WorkspaceLayout layout = new WorkspaceLayout(workspaceRoot);
    MigrationInspection inspection = inspect(workspaceRoot);
    Path manifestPath = layout.resolve("migrations/manifest.json");
    if (inspection.alreadyApplied() && Files.exists(manifestPath)) {
      return files.readObject(manifestPath);
    }
    if (!inspection.compatibility().compatible()) {
      throw new WorkspaceStoreException(
          "Workspace is not migration-compatible: " + String.join("; ", inspection.issues()));
    }

    String migrationId =
        "phase3-"
            + OffsetDateTime.now().toEpochSecond()
            + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    ObjectNode manifest = files.objectMapper().createObjectNode();
    manifest.put("manifest_version", "1.0");
    manifest.put("migration_id", migrationId);
    manifest.put("source_version", "python-legacy");
    manifest.put("target_version", TARGET_VERSION);
    manifest.put("state", "applying");
    manifest.put("created_at", OffsetDateTime.now().toString());
    ArrayNode entries = manifest.putArray("entries");
    snapshotEntries(layout.openGisRoot(), entries);
    files.write(manifestPath, manifest);

    ObjectNode marker = files.objectMapper().createObjectNode();
    marker.put("schema_version", "1.0");
    marker.put("storage_version", TARGET_VERSION);
    marker.put("migration_id", migrationId);
    marker.put("applied_at", OffsetDateTime.now().toString());
    marker.put("source_files_modified", false);
    files.write(layout.resolve("java-backend.json"), marker);

    manifest.put("state", "applied");
    manifest.put("applied_at", OffsetDateTime.now().toString());
    files.write(manifestPath, manifest);
    return manifest;
  }

  public synchronized ObjectNode rollback(Path workspaceRoot) {
    WorkspaceLayout layout = new WorkspaceLayout(workspaceRoot);
    Path manifestPath = layout.resolve("migrations/manifest.json");
    if (!Files.exists(manifestPath)) {
      throw new WorkspaceStoreException("Migration manifest does not exist: " + manifestPath);
    }
    ObjectNode manifest = files.readObject(manifestPath);
    if (!"applied".equals(manifest.path("state").asText())) {
      return manifest;
    }
    try {
      Files.deleteIfExists(layout.resolve("java-backend.json"));
    } catch (IOException exception) {
      throw new WorkspaceStoreException("Cannot remove Java migration marker", exception);
    }
    manifest.put("state", "rolled_back");
    manifest.put("rolled_back_at", OffsetDateTime.now().toString());
    files.write(manifestPath, manifest);
    return manifest;
  }

  private void snapshotEntries(Path openGisRoot, ArrayNode entries) {
    if (!Files.isDirectory(openGisRoot)) {
      return;
    }
    try (var paths = Files.walk(openGisRoot)) {
      List<Path> persistentFiles =
          paths
              .filter(Files::isRegularFile)
              .filter(path -> !path.startsWith(openGisRoot.resolve("migrations")))
              .filter(path -> !path.equals(openGisRoot.resolve("java-backend.json")))
              .sorted()
              .toList();
      for (Path path : persistentFiles) {
        ObjectNode entry = entries.addObject();
        entry.put("path", openGisRoot.relativize(path).toString().replace('\\', '/'));
        entry.put("bytes", Files.size(path));
        entry.put("sha256", sha256(path));
      }
    } catch (IOException exception) {
      throw new WorkspaceStoreException("Cannot inventory migration files", exception);
    }
  }

  private static String sha256(Path path) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new WorkspaceStoreException("Cannot hash migration file: " + path, exception);
    }
  }
}
