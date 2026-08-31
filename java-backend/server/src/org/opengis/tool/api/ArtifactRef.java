/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.api;

/** Pointer to a large or durable tool output materialized under the current run. */
public record ArtifactRef(
    String id, String title, String path, String mediaType, long sizeBytes, String sha256) {}
