/** 文件职责：code 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.code.runner;

import java.util.Map;

/** Parent callbacks; Tool calls must be implemented by routing through ToolRuntime. */
public interface ScriptCallbacks {
  Map<String, Object> callTool(String name, Map<String, Object> arguments);

  void mapEvent(String method, Map<String, Object> parameters);

  void event(String type, Map<String, Object> payload);

  static ScriptCallbacks disconnected() {
    return new ScriptCallbacks() {
      @Override
      public Map<String, Object> callTool(String name, Map<String, Object> arguments) {
        throw new IllegalStateException("Tool callback is unavailable");
      }

      @Override
      public void mapEvent(String method, Map<String, Object> parameters) {
        throw new IllegalStateException("Map callback is unavailable");
      }

      @Override
      public void event(String type, Map<String, Object> payload) {}
    };
  }
}
