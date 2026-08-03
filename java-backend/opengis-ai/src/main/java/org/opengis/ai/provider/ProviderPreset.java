package org.opengis.ai.provider;

/** One frontend-visible provider and its Java wire adapter. */
public record ProviderPreset(
    String id,
    String label,
    ProviderProtocol protocol,
    String baseUrl,
    String defaultModel,
    String supportTier) {}
