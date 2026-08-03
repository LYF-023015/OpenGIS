package org.opengis.tool.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Report, academic, debug, Script and Skill tools that do not require the Phase 5 Agent loop. */
final class KnowledgeTools {
  private KnowledgeTools() {}

  static List<OpenGisTool> create(ObjectMapper mapper) {
    List<OpenGisTool> tools = new ArrayList<>();
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
    tools.add(loadSkill(mapper));
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
          result.put("text", arguments.path("text").asText());
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
    Path directory = WorkspacePaths.resolve(context, arguments.path("output_dir").asText());
    Path report = directory.resolve("report.md");
    try {
      Files.createDirectories(directory);
      StringBuilder block = new StringBuilder();
      if (!Files.exists(report) && !arguments.path("title").asText("").isBlank()) {
        block.append("# ").append(arguments.path("title").asText()).append("\n\n");
      }
      block
          .append("## ")
          .append(arguments.path("heading").asText())
          .append("\n\n")
          .append(arguments.path("content").asText())
          .append("\n\n");
      Files.writeString(
          report,
          block,
          StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
      ObjectNode result = mapper.createObjectNode();
      result.put("path", report.toString());
      result.put("section", arguments.path("heading").asText());
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
              scriptRoot.resolve(arguments.path("path").asText()).toAbsolutePath().normalize();
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

  private static OpenGisTool loadSkill(ObjectMapper mapper) {
    return new FunctionalTool(
        new ToolDefinition(
            "load_skill",
            "Load Skill",
            "Load a workspace SKILL.md as instructions without executing embedded code.",
            "orchestration",
            "core",
            "1.0.0",
            ToolRisk.READ,
            ToolSchemas.object(mapper, Map.of("name", ToolSchemas.string(mapper)), "name"),
            List.of("skill")),
        (arguments, context) -> {
          String name = arguments.path("name").asText();
          if (!name.matches("[A-Za-z0-9._-]+")) {
            throw new ToolException("invalid_skill_name", "Invalid skill name");
          }
          Path path =
              context.workspace().resolve(".opengis/skills").resolve(name).resolve("SKILL.md");
          if (!Files.isRegularFile(path)) {
            throw new ToolException("skill_not_found", "Workspace skill not found: " + name);
          }
          try {
            return mapper
                .createObjectNode()
                .put("name", name)
                .put("content", Files.readString(path, StandardCharsets.UTF_8));
          } catch (IOException exception) {
            throw new ToolException("skill_read_failed", "Cannot read skill", exception);
          }
        });
  }

  private static String title(String value) {
    return value.replace('_', ' ');
  }
}
