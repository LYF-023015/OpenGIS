package org.opengis.ai.context;

/** Complete provider-request estimate and pressure classification. */
public record RequestBudget(
    int messageTokens,
    int toolSchemaTokens,
    int outputReserve,
    int contextWindow,
    Pressure pressure) {
  public enum Pressure {
    NORMAL,
    ELEVATED,
    HIGH,
    OVERFLOW
  }

  public int total() {
    return messageTokens + toolSchemaTokens + outputReserve;
  }

  public static RequestBudget evaluate(
      CanonicalRequest request, TokenEstimator estimator, int contextWindow) {
    int messages = estimator.messages(request.messages());
    int tools = estimator.tools(request.tools());
    int total = messages + tools + request.maxTokens();
    double ratio = contextWindow <= 0 ? 1.0 : (double) total / contextWindow;
    Pressure pressure =
        ratio > 1.0
            ? Pressure.OVERFLOW
            : ratio >= 0.9 ? Pressure.HIGH : ratio >= 0.75 ? Pressure.ELEVATED : Pressure.NORMAL;
    return new RequestBudget(messages, tools, request.maxTokens(), contextWindow, pressure);
  }
}
