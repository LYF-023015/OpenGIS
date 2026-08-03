package org.opengis.script.sdk;

import java.util.Map;

/** Stable single-entry contract for untrusted Java scripts executed in a child JVM. */
@FunctionalInterface
public interface OpenGisScript {
  Object run(ScriptContext context, Map<String, Object> parameters) throws Exception;
}
