package org.opengis.tool.skill;

/** A selected skill and the instruction body loaded for the current Agent turn. */
public record LoadedSkill(SkillDescriptor descriptor, String content) {}
