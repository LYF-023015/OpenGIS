/** 文件职责：tool 后端领域：管理状态或持久化数据。 */
package org.opengis.tool.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Discovers skill metadata first and loads the selected instruction body only on demand. */
public final class FileSystemSkillRepository {
  public static final long DEFAULT_MAX_SKILL_BYTES =
      SkillRepositorySettings.DEFAULT_MAX_SKILL_BYTES;
  public static final long DEFAULT_MAX_RESOURCE_BYTES =
      SkillRepositorySettings.DEFAULT_MAX_RESOURCE_BYTES;
  public static final int DEFAULT_MAX_RESOURCE_READ_CHARS =
      SkillRepositorySettings.DEFAULT_MAX_RESOURCE_READ_CHARS;
  public static final int DEFAULT_MAX_RESOURCES = SkillRepositorySettings.DEFAULT_MAX_RESOURCES;

  private static final Pattern FRONTMATTER =
      Pattern.compile("\\A---\\s*\\R(?<metadata>.*?)\\R---\\s*\\R?", Pattern.DOTALL);
  private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
  private static final Set<String> TEXT_EXTENSIONS =
      Set.of(
          "md",
          "txt",
          "json",
          "yaml",
          "yml",
          "csv",
          "tsv",
          "xml",
          "html",
          "css",
          "sql",
          "java",
          "py",
          "js",
          "ts",
          "tsx",
          "jsx",
          "sh",
          "ps1",
          "toml",
          "properties",
          "ini",
          "conf");

  private final Path globalRoot;
  private final List<Path> externalRoots;
  private final SkillRepositorySettings settings;

  public FileSystemSkillRepository() {
    this(SkillRepositorySettings.defaults());
  }

  public FileSystemSkillRepository(SkillRepositorySettings settings) {
    this(defaultGlobalRoot(), environmentRoots(), settings);
  }

  public FileSystemSkillRepository(long maxSkillBytes) {
    this(
        defaultGlobalRoot(),
        environmentRoots(),
        new SkillRepositorySettings(
            maxSkillBytes,
            DEFAULT_MAX_RESOURCE_BYTES,
            DEFAULT_MAX_RESOURCE_READ_CHARS,
            DEFAULT_MAX_RESOURCES,
            SkillRepositorySettings.DEFAULT_MAX_RUN_RESOURCE_CHARS));
  }

  FileSystemSkillRepository(Path globalRoot, List<Path> externalRoots, long maxSkillBytes) {
    this(
        globalRoot,
        externalRoots,
        new SkillRepositorySettings(
            maxSkillBytes,
            DEFAULT_MAX_RESOURCE_BYTES,
            DEFAULT_MAX_RESOURCE_READ_CHARS,
            DEFAULT_MAX_RESOURCES,
            SkillRepositorySettings.DEFAULT_MAX_RUN_RESOURCE_CHARS));
  }

  FileSystemSkillRepository(
      Path globalRoot, List<Path> externalRoots, SkillRepositorySettings settings) {
    this.globalRoot = globalRoot.toAbsolutePath().normalize();
    this.externalRoots =
        externalRoots.stream().map(Path::toAbsolutePath).map(Path::normalize).toList();
    this.settings = java.util.Objects.requireNonNull(settings, "settings");
  }

  public SkillRepositorySettings settings() {
    return settings;
  }

  public List<SkillDescriptor> discover(Path workspace) {
    Map<String, SkillDescriptor> skills = new LinkedHashMap<>();
    for (SkillRoot root : roots(workspace)) {
      discover(root).forEach(skill -> skills.put(skill.name(), skill));
    }
    return skills.values().stream()
        .sorted(Comparator.comparing(skill -> skill.name().toLowerCase(Locale.ROOT)))
        .toList();
  }

  public Optional<LoadedSkill> load(Path workspace, String name) {
    SkillDescriptor descriptor = select(workspace, name);
    if (descriptor == null) {
      return Optional.empty();
    }
    Path path = descriptor.location();
    try {
      long size = Files.size(path);
      if (size > settings.maxSkillBytes()) {
        throw new SkillRepositoryException(
            "skill_too_large",
            "Skill exceeds the maximum size of " + settings.maxSkillBytes() + " bytes: " + name);
      }
      return Optional.of(
          new LoadedSkill(descriptor, Files.readString(path, StandardCharsets.UTF_8)));
    } catch (IOException | SecurityException exception) {
      throw new SkillRepositoryException(
          "skill_read_failed", "Cannot read skill: " + name, exception);
    }
  }

  public List<SkillResourceDescriptor> listResources(Path workspace, String name) {
    SkillDescriptor descriptor = select(workspace, name);
    if (descriptor == null) {
      return List.of();
    }
    try {
      Path root = descriptor.location().getParent().toRealPath();
      try (var paths = Files.walk(root, 8)) {
        return paths
            .filter(path -> !"SKILL.md".equals(path.getFileName().toString()))
            .map(path -> resourceDescriptor(root, path))
            .flatMap(Optional::stream)
            .sorted(Comparator.comparing(SkillResourceDescriptor::path))
            .limit(settings.maxResources())
            .toList();
      }
    } catch (IOException | SecurityException exception) {
      throw new SkillRepositoryException(
          "skill_resource_list_failed", "Cannot list resources for skill: " + name, exception);
    }
  }

  public Optional<LoadedSkillResource> readResource(
      Path workspace, String name, String relativePath) {
    return readResource(workspace, name, relativePath, 0, settings.maxResourceReadChars());
  }

  public Optional<LoadedSkillResource> readResource(
      Path workspace, String name, String relativePath, int offset, int maxChars) {
    SkillDescriptor descriptor = select(workspace, name);
    if (descriptor == null) {
      return Optional.empty();
    }
    if (relativePath == null || relativePath.isBlank()) {
      throw new SkillRepositoryException("invalid_skill_resource", "Resource path is required");
    }
    if (offset < 0) {
      throw new SkillRepositoryException(
          "invalid_skill_resource_offset", "Resource offset must not be negative");
    }
    if (maxChars <= 0) {
      throw new SkillRepositoryException(
          "invalid_skill_resource_limit", "Resource maxChars must be positive");
    }
    try {
      Path requested = Path.of(relativePath);
      if (requested.isAbsolute()) {
        throw new SkillRepositoryException(
            "skill_resource_boundary", "Skill resource path must be relative");
      }
      Path root = descriptor.location().getParent().toRealPath();
      Path candidate = root.resolve(requested).normalize();
      if (!candidate.startsWith(root)) {
        throw new SkillRepositoryException(
            "skill_resource_boundary", "Skill resource escapes its package");
      }
      if (!Files.isRegularFile(candidate)) {
        return Optional.empty();
      }
      Path real = candidate.toRealPath();
      if (!real.startsWith(root)) {
        throw new SkillRepositoryException(
            "skill_resource_boundary", "Skill resource escapes its package");
      }
      SkillResourceDescriptor resource =
          resourceDescriptor(root, real)
              .orElseThrow(
                  () ->
                      new SkillRepositoryException(
                          "skill_resource_not_found", "Skill resource is not readable"));
      if (!resource.readable()) {
        throw new SkillRepositoryException(
            "skill_resource_binary", "Skill resource is not a supported text file");
      }
      if (resource.size() > settings.maxResourceBytes()) {
        throw new SkillRepositoryException(
            "skill_resource_too_large",
            "Skill resource exceeds " + settings.maxResourceBytes() + " bytes");
      }
      String content = Files.readString(real, StandardCharsets.UTF_8);
      if (offset > content.length()) {
        throw new SkillRepositoryException(
            "invalid_skill_resource_offset",
            "Resource offset exceeds the text length of " + content.length());
      }
      int boundedChars = Math.min(maxChars, settings.maxResourceReadChars());
      int nextOffset = Math.min(content.length(), offset + boundedChars);
      return Optional.of(
          new LoadedSkillResource(
              resource,
              content.substring(offset, nextOffset),
              offset,
              nextOffset,
              content.length(),
              nextOffset < content.length()));
    } catch (SkillRepositoryException exception) {
      throw exception;
    } catch (IOException | SecurityException | java.nio.file.InvalidPathException exception) {
      throw new SkillRepositoryException(
          "skill_resource_read_failed", "Cannot read skill resource", exception);
    }
  }

  private SkillDescriptor select(Path workspace, String name) {
    if (name == null || !VALID_NAME.matcher(name).matches()) {
      throw new SkillRepositoryException("invalid_skill_name", "Invalid skill name");
    }
    return discover(workspace).stream()
        .filter(skill -> skill.name().equals(name))
        .findFirst()
        .orElse(null);
  }

  private static Optional<SkillResourceDescriptor> resourceDescriptor(Path root, Path path) {
    try {
      if (!Files.isRegularFile(path)) {
        return Optional.empty();
      }
      Path real = path.toRealPath();
      if (!real.startsWith(root)) {
        return Optional.empty();
      }
      String relative = root.relativize(real).toString().replace('\\', '/');
      String first =
          relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : "other";
      return Optional.of(
          new SkillResourceDescriptor(relative, Files.size(real), first, isReadableText(relative)));
    } catch (IOException | SecurityException ignored) {
      return Optional.empty();
    }
  }

  private static boolean isReadableText(String path) {
    int separator = path.lastIndexOf('.');
    return separator >= 0
        && TEXT_EXTENSIONS.contains(path.substring(separator + 1).toLowerCase(Locale.ROOT));
  }

  private List<SkillRoot> roots(Path workspace) {
    List<SkillRoot> roots = new ArrayList<>();
    roots.add(new SkillRoot(globalRoot, "global"));
    externalRoots.forEach(path -> roots.add(new SkillRoot(path, "external")));
    if (workspace != null) {
      Path normalized = workspace.toAbsolutePath().normalize();
      roots.add(new SkillRoot(normalized.resolve(".agents/skills"), "workspace-compat"));
      roots.add(new SkillRoot(normalized.resolve("skills"), "workspace-compat"));
      roots.add(new SkillRoot(normalized.resolve(".opengis/skills"), "workspace"));
    }
    return roots;
  }

  private static Path defaultGlobalRoot() {
    return Path.of(System.getProperty("user.home"), ".opengis", "skills");
  }

  private static List<Path> environmentRoots() {
    String environment = System.getenv("OPENGIS_SKILL_PATHS");
    if (environment == null || environment.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(environment.split(Pattern.quote(java.io.File.pathSeparator)))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .map(Path::of)
        .toList();
  }

  private List<SkillDescriptor> discover(SkillRoot root) {
    if (!Files.isDirectory(root.path())) {
      return List.of();
    }
    try (var paths = Files.find(root.path(), 5, FileSystemSkillRepository::isSkillFile)) {
      return paths
          .sorted()
          .map(path -> read(path, root.source()))
          .flatMap(Optional::stream)
          .toList();
    } catch (IOException | SecurityException ignored) {
      return List.of();
    }
  }

  private Optional<SkillDescriptor> read(Path path, String source) {
    try {
      if (Files.size(path) > settings.maxSkillBytes()) {
        return Optional.empty();
      }
      String raw = Files.readString(path, StandardCharsets.UTF_8);
      Matcher frontmatter = FRONTMATTER.matcher(raw);
      Map<String, String> metadata =
          frontmatter.find() ? parseMetadata(frontmatter.group("metadata")) : Map.of();
      String name = metadata.getOrDefault("name", path.getParent().getFileName().toString()).trim();
      if (!VALID_NAME.matcher(name).matches()) {
        return Optional.empty();
      }
      Path location = path.toAbsolutePath().normalize();
      return Optional.of(
          new SkillDescriptor(
              name,
              metadata.getOrDefault("description", ""),
              location,
              source,
              tags(metadata.get("tags")),
              metadata.getOrDefault("version", "")));
    } catch (IOException | SecurityException ignored) {
      return Optional.empty();
    }
  }

  private static Map<String, String> parseMetadata(String source) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String line : source.lines().toList()) {
      int separator = line.indexOf(':');
      if (separator > 0) {
        values.put(
            line.substring(0, separator).trim(), unquote(line.substring(separator + 1).trim()));
      }
    }
    return values;
  }

  private static List<String> tags(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    String normalized =
        value.startsWith("[") && value.endsWith("]")
            ? value.substring(1, value.length() - 1)
            : value;
    return java.util.Arrays.stream(normalized.split(","))
        .map(String::trim)
        .map(FileSystemSkillRepository::unquote)
        .filter(item -> !item.isBlank())
        .toList();
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static boolean isSkillFile(
      Path path, java.nio.file.attribute.BasicFileAttributes attributes) {
    return attributes.isRegularFile() && "SKILL.md".equals(path.getFileName().toString());
  }

  private record SkillRoot(Path path, String source) {}
}
