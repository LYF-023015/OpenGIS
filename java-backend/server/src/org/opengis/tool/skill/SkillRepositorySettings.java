/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.skill;

/** Configurable safety and context-budget limits for filesystem-backed Skills. */
public record SkillRepositorySettings(
    long maxSkillBytes,
    long maxResourceBytes,
    int maxResourceReadChars,
    int maxResources,
    long maxRunResourceChars) {
  public static final long DEFAULT_MAX_SKILL_BYTES = 256L * 1024;
  public static final long DEFAULT_MAX_RESOURCE_BYTES = 10L * 1024 * 1024;
  public static final int DEFAULT_MAX_RESOURCE_READ_CHARS = 50_000;
  public static final int DEFAULT_MAX_RESOURCES = 512;
  public static final long DEFAULT_MAX_RUN_RESOURCE_CHARS = 200_000;

  public SkillRepositorySettings {
    maxSkillBytes = positive(maxSkillBytes, "maxSkillBytes");
    maxResourceBytes = positive(maxResourceBytes, "maxResourceBytes");
    maxResourceReadChars = positive(maxResourceReadChars, "maxResourceReadChars");
    maxResources = positive(maxResources, "maxResources");
    maxRunResourceChars = positive(maxRunResourceChars, "maxRunResourceChars");
  }

  public static SkillRepositorySettings defaults() {
    return new SkillRepositorySettings(
        DEFAULT_MAX_SKILL_BYTES,
        DEFAULT_MAX_RESOURCE_BYTES,
        DEFAULT_MAX_RESOURCE_READ_CHARS,
        DEFAULT_MAX_RESOURCES,
        DEFAULT_MAX_RUN_RESOURCE_CHARS);
  }

  private static long positive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static int positive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
