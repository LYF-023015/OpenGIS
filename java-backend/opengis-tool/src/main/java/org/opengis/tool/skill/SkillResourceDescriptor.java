package org.opengis.tool.skill;

/** Safe metadata for one file contained by a selected skill package. */
public record SkillResourceDescriptor(String path, long size, String kind, boolean readable) {}
