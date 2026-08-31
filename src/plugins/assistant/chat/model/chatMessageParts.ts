/** 文件职责：chat 前端功能：实现该文件名所对应的单一职责。 */
import type { MessagePart, ChatMessage } from "@/plugins/assistant/chat/model/chatTypes";

export function messagePartsForRender(message: ChatMessage): MessagePart[] {
  return message.parts ?? [];
}

export function upsertMessagePart(
  message: ChatMessage,
  incoming: MessagePart,
): ChatMessage {
  const current = message.parts ?? [];
  const index = current.findIndex((part) => part.id === incoming.id);
  if (index < 0) {
    return { ...message, parts: [...current, incoming] };
  }
  const existing = current[index];
  const merged: MessagePart = {
    ...existing,
    ...incoming,
    text: mergePartText(existing, incoming),
    data: {
      ...(existing.data ?? {}),
      ...(incoming.data ?? {}),
    },
  };
  return {
    ...message,
    parts: [...current.slice(0, index), merged, ...current.slice(index + 1)],
  };
}

export type MessageRole = "user" | "assistant" | "system";

export interface MessageGroupData {
  role: MessageRole;
  items: ChatMessage[];
}

export function roleOf(message: ChatMessage): MessageRole {
  return message.say === "user_feedback" ? "user" : "assistant";
}

export function groupMessages(messages: ChatMessage[]): MessageGroupData[] {
  const groups: MessageGroupData[] = [];
  for (const message of messages) {
    const role = roleOf(message);
    const last = groups[groups.length - 1];
    if (role === "assistant" && last?.role === "assistant") {
      last.items.push(message);
    } else {
      groups.push({ role, items: [message] });
    }
  }
  return groups;
}

function mergePartText(
  existing: MessagePart,
  incoming: MessagePart,
): string | undefined {
  const next = incoming.text ?? "";
  if (!next) return existing.text;
  if (incoming.status === "streaming") {
    const current = existing.text ?? "";
    const textMode = incoming.data?.text_mode;

    // Java sends the complete text accumulated so far for every streaming update.
    // Keep explicit delta support for older/other producers that send only new text.
    if (textMode === "snapshot") return next;
    if (textMode === "delta") return `${current}${next}`;

    // Compatibility with archived events created before text_mode was introduced.
    if (next.startsWith(current)) return next;
    return `${current}${next}`;
  }
  return next;
}
