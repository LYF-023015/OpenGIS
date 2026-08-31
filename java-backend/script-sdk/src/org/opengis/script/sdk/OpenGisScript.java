/** 文件职责：script 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.script.sdk;

import java.util.Map;

/** Stable single-entry contract for untrusted Java scripts executed in a child JVM. */
@FunctionalInterface
public interface OpenGisScript {
  Object run(ScriptContext context, Map<String, Object> parameters) throws Exception;
}
