/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.skill;

/** Safe metadata for one file contained by a selected skill package. */
public record SkillResourceDescriptor(String path, long size, String kind, boolean readable) {}
