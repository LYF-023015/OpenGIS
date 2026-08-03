package org.opengis.agent.persistence;

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
