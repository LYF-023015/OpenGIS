package org.opengis.platform.persistence;

/** Read-only compatibility result for one persistent store family. */
public record StoreInspection(
    String name,
    boolean required,
    boolean readable,
    int fileCount,
    int recordCount,
    String detail) {}
