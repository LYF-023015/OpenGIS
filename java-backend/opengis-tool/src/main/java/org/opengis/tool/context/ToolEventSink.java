package org.opengis.tool.context;

@FunctionalInterface
public interface ToolEventSink {
  void emit(ToolEvent event);

  static ToolEventSink noop() {
    return ignored -> {};
  }
}
