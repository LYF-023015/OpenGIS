package org.opengis.knowledge.memory.consolidation;

/** Observable result of one maintenance pass. */
public record MemoryConsolidationReport(
    int duplicatesArchived, int staleArchived, int overflowArchived) {}
