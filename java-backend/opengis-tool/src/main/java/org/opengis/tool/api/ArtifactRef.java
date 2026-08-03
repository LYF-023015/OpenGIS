package org.opengis.tool.api;

/** Pointer to a large or durable tool output materialized under the current run. */
public record ArtifactRef(
    String id, String title, String path, String mediaType, long sizeBytes, String sha256) {}
