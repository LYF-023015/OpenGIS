package org.opengis.agent.loop;

import org.opengis.ai.model.LlmResponse;

public record ProviderTurn(LlmResponse response, long durationMillis) {}
