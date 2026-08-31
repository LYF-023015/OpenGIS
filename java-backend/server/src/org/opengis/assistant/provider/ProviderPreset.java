/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.provider;

/** One frontend-visible provider and its Java wire adapter. */
public record ProviderPreset(
    String id,
    String label,
    ProviderProtocol protocol,
    String baseUrl,
    String defaultModel,
    String supportTier) {}
