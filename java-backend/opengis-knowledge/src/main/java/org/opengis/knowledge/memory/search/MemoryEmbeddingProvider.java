package org.opengis.knowledge.memory.search;

/** Pluggable embedding boundary; deployments may replace the deterministic local fallback. */
public interface MemoryEmbeddingProvider {
  double[] embed(String text);
}
