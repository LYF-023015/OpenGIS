/** 文件职责：platform 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;

/** Reusable generic type tokens for JSON object conversion without raw-type casts. */
public final class JsonTypeReferences {
  public static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP =
      new TypeReference<>() {};
  public static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
  public static final TypeReference<LinkedHashMap<String, Object>> STRING_OBJECT_LINKED_MAP =
      new TypeReference<>() {};

  private JsonTypeReferences() {}
}
