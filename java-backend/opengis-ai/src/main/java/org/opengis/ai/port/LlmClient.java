package org.opengis.ai.port;

import java.util.function.Consumer;
import org.opengis.ai.model.LlmChunk;
import org.opengis.ai.model.LlmRequest;
import org.opengis.ai.model.LlmResponse;
import org.opengis.framework.concurrent.CancellationSignal;

/** Hexagonal provider port used by the agent loop. */
@FunctionalInterface
public interface LlmClient {
  LlmResponse complete(
      LlmRequest request, Consumer<LlmChunk> onChunk, CancellationSignal cancellation);

  default LlmResponse complete(LlmRequest request) {
    return complete(request, ignored -> {}, CancellationSignal.NONE);
  }
}
