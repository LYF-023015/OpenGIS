/** 文件职责：platform 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.persistence;

/** Read-only compatibility result for one persistent store family. */
public record StoreInspection(
    String name,
    boolean required,
    boolean readable,
    int fileCount,
    int recordCount,
    String detail) {}
