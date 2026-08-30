package org.opengis.tool.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.skill.FileSystemSkillRepository;
import org.opengis.tool.skill.LoadedSkill;
import org.opengis.tool.skill.LoadedSkillResource;
import org.opengis.tool.skill.SkillDescriptor;
import org.opengis.tool.skill.SkillRepositoryException;
import org.opengis.tool.skill.SkillResourceDescriptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Report, academic, debug, Script and Skill tools that do not require the Phase 5 Agent loop. */
final class KnowledgeTools {
  private KnowledgeTools() {}

  static List<OpenGisTool> create(ObjectMapper mapper) {
    return create(mapper, new FileSystemSkillRepository());
  }

  static List<OpenGisTool> create(ObjectMapper mapper, FileSystemSkillRepository skills) {
    List<OpenGisTool> tools = new ArrayList<>();
    SkillReadBudget readBudget = new SkillReadBudget();
    tools.add(academic(mapper, "academic_polish", "Polish academic text", "polish"));
    tools.add(academic(mapper, "academic_translate", "Translate academic text", "translate"));
    tools.add(
        academic(mapper, "academic_grammar_check", "Check grammar and spelling", "grammar_check"));
    tools.add(
        academic(
            mapper, "generate_abstract", "Generate a structured abstract", "generate_abstract"));
    tools.add(
        academic(mapper, "format_references", "Format academic references", "format_references"));
    tools.add(reportWriter(mapper));
    tools.add(debugContext(mapper));
    tools.add(listScripts(mapper));
    tools.add(readScript(mapper));
    tools.add(listSkills(mapper, skills));
    tools.add(loadSkill(mapper, skills));
    tools.add(listSkillResources(mapper, skills));
    tools.add(readSkillResource(mapper, skills, readBudget));
    tools.addAll(MemoryTools.create(mapper));
    return List.copyOf(tools);
  }

  private static OpenGisTool academic(
      ObjectMapper mapper, String name, String instruction, String action) {
    JsonNode schema =
        ToolSchemas.object(
            mapper,
            Map.of(
                "text", ToolSchemas.string(mapper),
                "language", ToolSchemas.optionalString(mapper),
                "target_lang", ToolSchemas.optionalString(mapper),
                "style", ToolSchemas.optionalString(mapper),
                "preserve_terms", ToolSchemas.optionalString(mapper),
                "max_words", ToolSchemas.integer(mapper, 1, 20_000),
                "format", ToolSchemas.optionalString(mapper)),
            "text");
    return new FunctionalTool(
        new ToolDefinition(
            name,
            title(name),
            instruction + "; returns provider-neutral instructions for the Agent.",
            "writing",
            "report",
            "1.0.0",
            ToolRisk.READ,
            schema,
            List.of("academic")),
        (arguments, context) -> {
          ObjectNode result = mapper.createObjectNode();
          result.put("instruction", instruction);
          result.put("text", arguments.path("text").asString());
          result.put("action", action);
          arguments
              .properties()
              .forEach(
                  entry -> {
                    if (!"text".equals(entry.getKey())) {
                      result.set(entry.getKey(), entry.getValue());
                    }
                  });
          return result;
        });
  }

  private static OpenGisTool reportWriter(ObjectMapper mapper) {
    JsonNode schema =
        ToolSchemas.object(
            mapper,
            Map.of(
                "output_dir", ToolSchemas.string(mapper),
                "title", ToolSchemas.optionalString(mapper),
                "heading", ToolSchemas.string(mapper),
                "content", ToolSchemas.optionalString(mapper),
                "figures", ToolSchemas.optionalString(mapper)),
            "output_dir",
            "heading",
            "content");
    return new FunctionalTool(
        new ToolDefinition(
            "write_report_section",
            "Write Report Section",
            "Append one Markdown section and keep report assets inside the workspace.",
            "report",
            "report",
            "1.0.0",
            ToolRisk.WRITE,
            schema,
            List.of("markdown")),
        (arguments, context) -> writeReport(arguments, context, mapper));
  }

  private static JsonNode writeReport(
      JsonNode arguments, ToolExecutionContext context, ObjectMapper mapper) {
    Path directory = WorkspacePaths.resolve(context, arguments.path("output_dir").asString());
    Path report = directory.resolve("report.md");
    try {
      Files.createDirectories(directory);
      StringBuilder block = new StringBuilder();
      if (!Files.exists(report) && !arguments.path("title").asString("").isBlank()) {
        block.append("# ").append(arguments.path("title").asString()).append("\n\n");
      }
      block
          .append("## ")
          .append(arguments.path("heading").asString())
          .append("\n\n")
          .append(arguments.path("content").asString())
          .append("\n\n");
      Files.writeString(
          report,
          block,
          StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
      ObjectNode result = mapper.createObjectNode();
      result.put("path", report.toString());
      result.put("section", arguments.path("heading").asString());
      return result;
    } catch (IOException exception) {
      throw new ToolException("report_write_failed", "Cannot write report", exception);
    }
  }

  private static OpenGisTool debugContext(ObjectMapper mapper) {
    return new FunctionalTool(
        new ToolDefinition(
            "debug_agent_context",
            "Debug Agent Context",
            "Return the non-secret execution identity visible to tools.",
            "system",
            "core",
            "1.0.0",
            ToolRisk.READ,
            ToolSchemas.object(mapper, Map.of()),
            List.of("debug")),
        (arguments, context) ->
            mapper.valueToTree(
                Map.of(
                    "workspace", context.workspace().toString(),
                    "run_id", context.runId(),
                    "profile_name", context.profileName(),
                    "cancelled", context.cancellation().isCancelled())));
  }

  private static OpenGisTool listScripts(ObjectMapper mapper) {
    return new FunctionalTool(
        new ToolDefinition(
            "list_scripts",
            "List Reusable Scripts",
            "List Java/Python legacy script archive metadata without executing it.",
            "system",
            "core",
            "1.0.0",
            ToolRisk.READ,
            ToolSchemas.object(mapper, Map.of()),
            List.of("script", "legacy-reader")),
        (arguments, context) -> {
          Path root = context.workspace().resolve(".opengis/scripts");
          ArrayNode values = mapper.createArrayNode();
          if (Files.isDirectory(root)) {
            try (Stream<Path> paths = Files.walk(root, 3)) {
              paths
                  .filter(path -> path.getFileName().toString().endsWith(".json"))
                  .sorted()
                  .forEach(
                      path -> {
                        ObjectNode row = values.addObject();
                        row.put("path", root.relativize(path).toString().replace('\\', '/'));
                        row.put("name", path.getFileName().toString());
                      });
            } catch (IOException exception) {
              throw new ToolException("script_list_failed", "Cannot list scripts", exception);
            }
          }
          return mapper.createObjectNode().set("scripts", values);
        });
  }

  private static OpenGisTool readScript(ObjectMapper mapper) {
    return new FunctionalTool(
        new ToolDefinition(
            "read_script",
            "Read Reusable Script",
            "Read an archived script as data; Phase 8B owns Java execution.",
            "system",
            "core",
            "1.0.0",
            ToolRisk.READ,
            ToolSchemas.object(mapper, Map.of("path", ToolSchemas.string(mapper)), "path"),
            List.of("script", "legacy-reader")),
        (arguments, context) -> {
          Path scriptRoot = context.workspace().resolve(".opengis/scripts").normalize();
          Path path =
              scriptRoot.resolve(arguments.path("path").asString()).toAbsolutePath().normalize();
          if (!path.startsWith(scriptRoot) || !Files.isRegularFile(path)) {
            throw new ToolException("script_not_found", "Script archive entry not found");
          }
          try {
            return mapper
                .createObjectNode()
                .put("path", path.toString())
                .put("content", Files.readString(path, StandardCharsets.UTF_8));
          } catch (IOException exception) {
            throw new ToolException("script_read_failed", "Cannot read script", exception);
          }
        });
  }

  private static OpenGisTool listSkills(ObjectMapper mapper, FileSystemSkillRepository repository) {
    return new FunctionalTool(
        new ToolDefinition(
            "list_skills",
            "List Skills",
            "Discover compact skill metadata. Use load_skill only for a relevant skill.",
            "orchestration",
            "core",
            "1.0.0",
            ToolRisk.READ,
            ToolSchemas.object(mapper, Map.of("query", ToolSchemas.optionalString(mapper))),
            List.of("skill", "discovery")),
        (arguments, context) -> {
          String query =
              arguments.path("query").asString("").trim().toLowerCase(java.util.Locale.ROOT);
          ArrayNode values = mapper.createArrayNode();
          repository.discover(context.workspace()).stream()
              .filter(skill -> matches(skill, query))
              .forEach(skill -> values.add(skillMetadata(mapper, skill)));
          return mapper.createObjectNode().set("skills", values);
        });
  }

  private static OpenGisTool loadSkill(ObjectMapper mapper, FileSystemSkillRepository repository) {
    return new FunctionalTool(
        new ToolDefinition(
            "load_skill",
            "Load Skill",
            "Load the selected global or workspace SKILL.md without executing embedded code.",
            "orchestration",
            "core",
            "1.0.0",
            ToolRisk.READ,
            ToolSchemas.object(mapper, Map.of("name", ToolSchemas.string(mapper)), "name"),
            List.of("skill")),
        (arguments, context) -> {
          String name = arguments.path("name").asString();
          try {
            LoadedSkill skill =
                repository
                    .load(context.workspace(), name)
                    .orElseThrow(
                        () ->
                            new ToolException(
                                "skill_not_found", "Skill not found in configured roots: " + name));
            ObjectNode result = skillMetadata(mapper, skill.descriptor());
            result.put("content", skill.content());
            return result;
          } catch (SkillRepositoryException exception) {
            throw new ToolException(exception.code(), exception.getMessage(), exception);
          }
        });
  }

  private static OpenGisTool listSkillResources(
      ObjectMapper mapper, FileSystemSkillRepository repository) {
    return new FunctionalTool(
        new ToolDefinition(
            "list_skill_resources",
            "List Skill Resources",
            "List references, templates, scripts and assets declared inside a skill package.",
            "orchestration",
            "core",
            "1.0.0",
            ToolRisk.READ,
            ToolSchemas.object(mapper, Map.of("name", ToolSchemas.string(mapper)), "name"),
            List.of("skill", "resource", "discovery")),
        (arguments, context) -> {
          String name = arguments.path("name").asString();
          try {
            List<SkillResourceDescriptor> resources =
                repository.listResources(context.workspace(), name);
            if (resources.isEmpty() && repository.load(context.workspace(), name).isEmpty()) {
              throw new ToolException(
                  "skill_not_found", "Skill not found in configured roots: " + name);
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("name", name);
            result.set("resources", mapper.valueToTree(resources));
            return result;
          } catch (SkillRepositoryException exception) {
            throw new ToolException(exception.code(), exception.getMessage(), exception);
          }
        });
  }

  private static OpenGisTool readSkillResource(
      ObjectMapper mapper, FileSystemSkillRepository repository, SkillReadBudget readBudget) {
    JsonNode schema =
        ToolSchemas.object(
            mapper,
            Map.of(
                "name", ToolSchemas.string(mapper),
                "path", ToolSchemas.string(mapper),
                "offset", ToolSchemas.integer(mapper, 0, Integer.MAX_VALUE),
                "max_chars",
                    ToolSchemas.integer(mapper, 1, repository.settings().maxResourceReadChars())),
            "name",
            "path");
    return new FunctionalTool(
        new ToolDefinition(
            "read_skill_resource",
            "Read Skill Resource",
            "Read one bounded text slice relative to a skill package. Use offset to continue; never executes script content.",
            "orchestration",
            "core",
            "1.0.0",
            ToolRisk.READ,
            schema,
            List.of("skill", "resource")),
        (arguments, context) -> {
          String name = arguments.path("name").asString();
          String path = arguments.path("path").asString();
          int offset = arguments.path("offset").asInt(0);
          int maxChars =
              arguments.path("max_chars").asInt(repository.settings().maxResourceReadChars());
          try {
            LoadedSkillResource resource =
                repository
                    .readResource(context.workspace(), name, path, offset, maxChars)
                    .orElseThrow(
                        () ->
                            new ToolException(
                                "skill_resource_not_found",
                                "Resource not found in skill " + name + ": " + path));
            long consumed =
                readBudget.consume(
                    context,
                    resource.content().length(),
                    repository.settings().maxRunResourceChars());
            ObjectNode result = mapper.createObjectNode();
            result.put("name", name);
            result.put("path", resource.descriptor().path());
            result.put("kind", resource.descriptor().kind());
            result.put("size", resource.descriptor().size());
            result.put("content", resource.content());
            result.put("offset", resource.offset());
            result.put("next_offset", resource.nextOffset());
            result.put("total_chars", resource.totalChars());
            result.put("truncated", resource.truncated());
            result.put("run_resource_chars", consumed);
            result.put(
                "run_resource_chars_remaining",
                repository.settings().maxRunResourceChars() - consumed);
            return result;
          } catch (SkillRepositoryException exception) {
            throw new ToolException(exception.code(), exception.getMessage(), exception);
          }
        });
  }

  /** Small bounded ledger preventing one Agent Run from repeatedly flooding its context. */
  private static final class SkillReadBudget {
    private static final int MAX_TRACKED_RUNS = 4096;
    private final LinkedHashMap<String, Long> consumedByRun =
        new LinkedHashMap<>(64, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_TRACKED_RUNS;
          }
        };

    synchronized long consume(
        ToolExecutionContext context, int characters, long maximumCharacters) {
      String key =
          context.workspace()
              + "\n"
              + context.runId()
              + "\n"
              + String.valueOf(context.conversationId());
      long current = consumedByRun.getOrDefault(key, 0L);
      long updated = current + characters;
      if (updated > maximumCharacters) {
        throw new ToolException(
            "skill_run_resource_budget_exceeded",
            "Skill resource reads exceed the per-run budget of "
                + maximumCharacters
                + " characters");
      }
      consumedByRun.put(key, updated);
      return updated;
    }
  }

  private static boolean matches(SkillDescriptor skill, String query) {
    if (query.isBlank()) {
      return true;
    }
    if (skill.name().toLowerCase(java.util.Locale.ROOT).contains(query)
        || skill.description().toLowerCase(java.util.Locale.ROOT).contains(query)) {
      return true;
    }
    return skill.tags().stream()
        .map(tag -> tag.toLowerCase(java.util.Locale.ROOT))
        .anyMatch(tag -> tag.contains(query));
  }

  private static ObjectNode skillMetadata(ObjectMapper mapper, SkillDescriptor skill) {
    ObjectNode result = mapper.createObjectNode();
    result.put("name", skill.name());
    result.put("description", skill.description());
    result.put("source", skill.source());
    result.put("version", skill.version());
    result.set("tags", mapper.valueToTree(skill.tags()));
    return result;
  }

  private static String title(String value) {
    return value.replace('_', ' ');
  }
}
