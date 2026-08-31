/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.skill;

/** Stable failure raised while discovering or reading a filesystem-backed skill. */
public final class SkillRepositoryException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public SkillRepositoryException(String code, String message) {
    super(message);
    this.code = code;
  }

  public SkillRepositoryException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
