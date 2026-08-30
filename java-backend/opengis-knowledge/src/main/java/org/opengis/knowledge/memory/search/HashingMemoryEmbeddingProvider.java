package org.opengis.knowledge.memory.search;

import java.util.Locale;

/** Offline-safe feature hashing used when no external embedding provider is configured. */
public final class HashingMemoryEmbeddingProvider implements MemoryEmbeddingProvider {
  private static final int DIMENSIONS = 256;

  @Override
  public double[] embed(String text) {
    double[] vector = new double[DIMENSIONS];
    String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    if (normalized.isBlank()) {
      return vector;
    }
    String padded = "  " + normalized + "  ";
    for (int index = 0; index + 2 < padded.length(); index++) {
      String gram = padded.substring(index, index + 3);
      int hash = gram.hashCode();
      int bucket = Math.floorMod(hash, DIMENSIONS);
      vector[bucket] += (hash & 1) == 0 ? 1.0 : -1.0;
    }
    normalize(vector);
    return vector;
  }

  private static void normalize(double[] vector) {
    double norm = 0.0;
    for (double value : vector) {
      norm += value * value;
    }
    if (norm == 0.0) {
      return;
    }
    norm = Math.sqrt(norm);
    for (int index = 0; index < vector.length; index++) {
      vector[index] /= norm;
    }
  }
}
