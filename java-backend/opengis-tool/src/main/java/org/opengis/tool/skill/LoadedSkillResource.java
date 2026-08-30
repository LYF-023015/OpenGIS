package org.opengis.tool.skill;

/** One bounded text slice loaded from inside a selected skill package. */
public record LoadedSkillResource(
    SkillResourceDescriptor descriptor,
    String content,
    int offset,
    int nextOffset,
    int totalChars,
    boolean truncated) {}
