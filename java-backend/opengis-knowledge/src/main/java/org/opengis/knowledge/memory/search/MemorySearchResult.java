package org.opengis.knowledge.memory.search;

import org.opengis.knowledge.memory.MemoryRecord;

/** Explainable hybrid score returned by the retrieval layer. */
public record MemorySearchResult(
    MemoryRecord record,
    double score,
    double lexicalScore,
    double vectorScore,
    double recencyScore) {}
