/** 文件职责：knowledge 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.memory.search;

/** Pluggable embedding boundary; deployments may replace the deterministic local fallback. */
public interface MemoryEmbeddingProvider {
  double[] embed(String text);
}
