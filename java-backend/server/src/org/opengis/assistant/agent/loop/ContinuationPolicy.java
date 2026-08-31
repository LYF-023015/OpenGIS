/** 文件职责：实现 ContinuationPolicy 的单一职责；调用关系从同级主入口文件进入。 */
package org.opengis.assistant.agent.loop;

import java.util.List;
import java.util.Locale;

/** Detects a model promising future work without issuing the required tool call. */
public final class ContinuationPolicy {
  private static final List<String> PROMISES =
      List.of("next i will", "i will now", "i'll now", "下一步", "接下来我会", "接下来将", "还需要调用", "需要先运行");

  public boolean isDeviation(String text) {
    String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
    return PROMISES.stream().anyMatch(value::contains);
  }
}
