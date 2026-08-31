/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.model.context;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.opengis.assistant.model.LlmUsage;
import tools.jackson.databind.ObjectMapper;

/** Process-local prompt-cache diagnostics without retaining prompt content or API keys. */
public final class CacheObservatory {
  private final ObjectMapper mapper;
  private final ConcurrentHashMap<String, PrefixState> prefixes = new ConcurrentHashMap<>();
  private final LongAdder requests = new LongAdder();
  private final LongAdder promptTokens = new LongAdder();
  private final LongAdder cachedTokens = new LongAdder();
  private final LongAdder cacheCreationTokens = new LongAdder();
  private final LongAdder systemPrefixChanges = new LongAdder();

  public CacheObservatory(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void record(String provider, CanonicalRequest request, LlmUsage usage) {
    requests.increment();
    promptTokens.add(usage.promptTokens());
    cachedTokens.add(usage.cachedTokens());
    cacheCreationTokens.add(usage.cacheCreationTokens());
    String key = provider + ":" + request.model();
    String stableHash = request.stableSystemHash(mapper);
    prefixes.compute(
        key,
        (ignored, previous) -> {
          if (previous != null && !previous.systemHash.equals(stableHash)) {
            systemPrefixChanges.increment();
          }
          return new PrefixState(stableHash, request.cacheablePrefixHash(mapper), Instant.now());
        });
  }

  public Map<String, Object> snapshot() {
    long input = promptTokens.sum();
    return Map.of(
        "requests", requests.sum(),
        "prompt_tokens", input,
        "cached_tokens", cachedTokens.sum(),
        "cache_creation_tokens", cacheCreationTokens.sum(),
        "cache_hit_rate", input == 0 ? 0.0 : Math.min(1.0, (double) cachedTokens.sum() / input),
        "system_prefix_changes", systemPrefixChanges.sum(),
        "tracked_routes", prefixes.size());
  }

  private record PrefixState(String systemHash, String cacheableHash, Instant observedAt) {}
}
