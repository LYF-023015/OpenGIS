/** 文件职责：agent 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.agent.persistence;

/** Lightweight run row used by list views. */
public record RunIndex(
    String runId,
    String status,
    String prompt,
    String createdAt,
    String finishedAt,
    int stepCount,
    String preSha,
    String postSha) {}
