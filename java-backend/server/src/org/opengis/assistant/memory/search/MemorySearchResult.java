/** 文件职责：knowledge 后端领域：定义领域数据结构与协议。 */
package org.opengis.assistant.memory.search;

import org.opengis.assistant.memory.MemoryRecord;

/** Explainable hybrid score returned by the retrieval layer. */
public record MemorySearchResult(
    MemoryRecord record,
    double score,
    double lexicalScore,
    double vectorScore,
    double recencyScore) {}
