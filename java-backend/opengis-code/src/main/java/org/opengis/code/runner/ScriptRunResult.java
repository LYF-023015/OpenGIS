package org.opengis.code.runner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Terminal child-process result with durable log and artifact pointers. */
public record ScriptRunResult(
    String runId,
    String status,
    Object output,
    String error,
    int exitCode,
    Instant startedAt,
    Instant finishedAt,
    Path stdoutPath,
    Path stderrPath,
    boolean stdoutTruncated,
    boolean stderrTruncated,
    List<Map<String, Object>> artifacts,
    List<Map<String, Object>> progress,
    String sourceSha256,
    List<String> dependencyChecksums) {}
