/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.skill;

/** One bounded text slice loaded from inside a selected skill package. */
public record LoadedSkillResource(
    SkillResourceDescriptor descriptor,
    String content,
    int offset,
    int nextOffset,
    int totalChars,
    boolean truncated) {}
