/** 文件职责：script 后端领域：封装外部系统或通信协议。 */
package org.opengis.script.sdk;

import java.util.Map;

/** Child-side transport implemented by the bundled host, not by user scripts. */
public interface ProtocolTransport {
  Map<String, Object> request(String type, Map<String, Object> payload);

  void emit(String type, Map<String, Object> payload);

  boolean isCancelled();
}
