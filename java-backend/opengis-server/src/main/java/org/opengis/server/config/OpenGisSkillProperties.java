package org.opengis.server.config;

import org.opengis.tool.skill.SkillRepositorySettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configurable Skill package and model-context safety limits. */
@ConfigurationProperties(prefix = "opengis.skills")
public class OpenGisSkillProperties {
  private long maxSkillBytes = SkillRepositorySettings.DEFAULT_MAX_SKILL_BYTES;
  private long maxResourceBytes = SkillRepositorySettings.DEFAULT_MAX_RESOURCE_BYTES;
  private int maxResourceReadChars = SkillRepositorySettings.DEFAULT_MAX_RESOURCE_READ_CHARS;
  private int maxResources = SkillRepositorySettings.DEFAULT_MAX_RESOURCES;
  private long maxRunResourceChars = SkillRepositorySettings.DEFAULT_MAX_RUN_RESOURCE_CHARS;

  public long getMaxSkillBytes() {
    return maxSkillBytes;
  }

  public void setMaxSkillBytes(long maxSkillBytes) {
    this.maxSkillBytes = maxSkillBytes;
  }

  public long getMaxResourceBytes() {
    return maxResourceBytes;
  }

  public void setMaxResourceBytes(long maxResourceBytes) {
    this.maxResourceBytes = maxResourceBytes;
  }

  public int getMaxResourceReadChars() {
    return maxResourceReadChars;
  }

  public void setMaxResourceReadChars(int maxResourceReadChars) {
    this.maxResourceReadChars = maxResourceReadChars;
  }

  public int getMaxResources() {
    return maxResources;
  }

  public void setMaxResources(int maxResources) {
    this.maxResources = maxResources;
  }

  public long getMaxRunResourceChars() {
    return maxRunResourceChars;
  }

  public void setMaxRunResourceChars(long maxRunResourceChars) {
    this.maxRunResourceChars = maxRunResourceChars;
  }

  SkillRepositorySettings toSettings() {
    return new SkillRepositorySettings(
        maxSkillBytes, maxResourceBytes, maxResourceReadChars, maxResources, maxRunResourceChars);
  }
}
