/** 文件职责：ai 后端领域：提供聚焦的辅助函数。 */
package org.opengis.assistant.provider;

import org.springframework.ai.chat.model.ChatModel;

/** Provider model creation port used by server composition and tests. */
@FunctionalInterface
public interface LlmModelFactory {
  ChatModel createChatModel(ProviderConfig config);
}
