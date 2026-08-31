/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.skill;

import java.nio.file.Path;
import java.util.List;

/** Metadata used to discover a filesystem-backed instruction skill without loading its body. */
public record SkillDescriptor(
    String name,
    String description,
    Path location,
    String source,
    List<String> tags,
    String version) {}
