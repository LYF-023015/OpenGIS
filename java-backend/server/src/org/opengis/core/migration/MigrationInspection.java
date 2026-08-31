/** 文件职责：platform 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.migration;

import java.util.List;
import org.opengis.core.persistence.WorkspaceCompatibilityReport;

/** Non-mutating answer from the Phase 3 migration inspector. */
public record MigrationInspection(
    boolean applicable,
    boolean alreadyApplied,
    String currentVersion,
    String targetVersion,
    WorkspaceCompatibilityReport compatibility,
    List<String> issues) {}
