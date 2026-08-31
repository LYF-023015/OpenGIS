/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.skill;

/** A selected skill and the instruction body loaded for the current Agent turn. */
public record LoadedSkill(SkillDescriptor descriptor, String content) {}
