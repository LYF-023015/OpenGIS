/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.error;

/** Expected GIS domain failure with a stable machine-readable code. */
public final class GisException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public GisException(String code, String message) {
    this(code, message, null);
  }

  public GisException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
