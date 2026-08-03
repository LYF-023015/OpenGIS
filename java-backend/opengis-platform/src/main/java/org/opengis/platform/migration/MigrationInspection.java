package org.opengis.platform.migration;

import java.util.List;
import org.opengis.platform.persistence.WorkspaceCompatibilityReport;

/** Non-mutating answer from the Phase 3 migration inspector. */
public record MigrationInspection(
    boolean applicable,
    boolean alreadyApplied,
    String currentVersion,
    String targetVersion,
    WorkspaceCompatibilityReport compatibility,
    List<String> issues) {}
