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
