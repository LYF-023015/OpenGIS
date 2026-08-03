package org.opengis.ai.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.opengis.ai.model.LlmMessage;
import org.opengis.ai.model.LlmRequest;
import org.opengis.ai.model.LlmToolDefinition;
import tools.jackson.databind.ObjectMapper;

/** Canonical section layout and stable hashes for one provider call. */
public record CanonicalRequest(
    String model,
    List<PromptSection> sections,
    List<LlmToolDefinition> tools,
    double temperature,
    int maxTokens,
    java.time.Duration timeout,
    Map<String, Object> metadata) {
  public CanonicalRequest {
    sections = sections == null ? List.of() : List.copyOf(sections);
    tools = tools == null ? List.of() : List.copyOf(tools);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    validateOrder(sections);
  }

  public List<LlmMessage> messages() {
    return sections.stream().flatMap(section -> section.messages().stream()).toList();
  }

  public LlmRequest toLlmRequest() {
    return new LlmRequest(model, messages(), tools, temperature, maxTokens, timeout, metadata);
  }

  public String cacheablePrefixHash(ObjectMapper mapper) {
    List<Object> prefix = new ArrayList<>();
    for (PromptSection section : sections) {
      if (section.cachePolicy() == PromptCachePolicy.NONE) {
        break;
      }
      prefix.add(
          Map.of(
              "id", section.id(),
              "kind", section.kind().name(),
              "stability", section.stability().name(),
              "messages", section.messages()));
    }
    return sha256(mapper.writeValueAsString(prefix));
  }

  public String stableSystemHash(ObjectMapper mapper) {
    List<Object> prefix = new ArrayList<>();
    for (PromptSection section : sections) {
      if (section.kind() == PromptSectionKind.HISTORY) {
        break;
      }
      prefix.add(
          Map.of(
              "id", section.id(), "kind", section.kind().name(), "messages", section.messages()));
    }
    return sha256(mapper.writeValueAsString(prefix));
  }

  private static void validateOrder(List<PromptSection> sections) {
    int previous = -1;
    for (PromptSection section : sections) {
      int current = section.kind().ordinal();
      if (current < previous) {
        throw new IllegalArgumentException(
            "Prompt sections are not in canonical order: " + section.id());
      }
      previous = current;
    }
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK does not provide SHA-256", exception);
    }
  }
}
