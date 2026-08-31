/** 文件职责：chat 前端功能：实现该文件名所对应的单一职责。 */
import type { MessagePart, ChatMessage } from "./chatTypes";
import type { ChatStore, Conversation } from "./chatStore";
import { upsertMessagePart } from "./chatMessageParts";
let _pendingCodePart: MessagePart | null = null;
let _pendingCodePartTimer: ReturnType<typeof setTimeout> | null = null;

function codePartFlushMs(codeLength: number): number {
  if (codeLength > 24_000) return 240;
  if (codeLength > 12_000) return 160;
  if (codeLength > 6_000) return 100;
  if (codeLength > 2_000) return 66;
  return 33;
}

export function isStreamingCodePart(part: MessagePart): boolean {
  return part.type === "code" && part.status === "streaming";
}

function mergeBufferedCodePart(
  existing: MessagePart,
  incoming: MessagePart,
): MessagePart {
  return {
    ...existing,
    ...incoming,
    text: `${existing.text ?? ""}${incoming.text ?? ""}`,
    data: {
      ...(existing.data ?? {}),
      ...(incoming.data ?? {}),
    },
  };
}

export function flushBufferedCodePart(
  set: (
    partial: Partial<ChatStore> | ((state: ChatStore) => Partial<ChatStore>),
  ) => void,
): void {
  if (_pendingCodePartTimer) {
    clearTimeout(_pendingCodePartTimer);
    _pendingCodePartTimer = null;
  }
  const part = _pendingCodePart;
  _pendingCodePart = null;
  if (!part) return;
  set((s) => ({
    conversations: s.conversations.map((conversation) =>
      conversation.id === s.activeConversationId
        ? upsertNativePartIntoConversation(conversation, part)
        : conversation,
    ),
  }));
}

export function enqueueStreamingCodePart(
  part: MessagePart,
  set: (
    partial: Partial<ChatStore> | ((state: ChatStore) => Partial<ChatStore>),
  ) => void,
): void {
  if (_pendingCodePart && _pendingCodePart.id !== part.id) {
    flushBufferedCodePart(set);
  }
  _pendingCodePart = _pendingCodePart
    ? mergeBufferedCodePart(_pendingCodePart, part)
    : part;
  if (_pendingCodePartTimer) return;
  _pendingCodePartTimer = setTimeout(
    () => {
      flushBufferedCodePart(set);
    },
    codePartFlushMs((_pendingCodePart.text ?? "").length),
  );
}

export function settleRunningStatusCards(
  conv: Conversation | null,
  updateMessage: (ts: number, updates: Partial<ChatMessage>) => void,
  mode: "completed" | "failed" | "cancelled",
): void {
  if (!conv) return;
  for (const msg of conv.messages) {
    if (
      msg.say === "plan" &&
      msg.planData?.steps?.some((s: any) => s.status === "in_progress")
    ) {
      const planData = {
        ...msg.planData,
        steps: msg.planData.steps.map((s: any) =>
          s.status === "in_progress"
            ? { ...s, status: mode === "completed" ? "done" : "failed" }
            : s,
        ),
        updatedAt: Date.now(),
      };
      updateMessage(msg.ts, {
        planData,
        parts: msg.parts?.map((part) =>
          part.type === "plan"
            ? {
                ...part,
                status: mode === "completed" ? "completed" : "failed",
                data: { ...(part.data ?? {}), planData },
              }
            : part,
        ),
      });
    }

    if (msg.say === "subagent" && msg.subagentData?.status === "running") {
      const finalStatus: NonNullable<ChatMessage["subagentData"]>["status"] =
        mode === "completed" ? "done" : mode;
      const subagentData = {
        ...msg.subagentData,
        status: finalStatus,
        tasks: msg.subagentData.tasks.map((task: any) =>
          task.status === "running"
            ? { ...task, status: mode === "completed" ? "done" : mode }
            : task,
        ),
        updatedAt: Date.now(),
      };
      updateMessage(msg.ts, {
        subagentData,
        parts: msg.parts?.map((part) =>
          part.type === "progress" && part.data?.kind === "subagent"
            ? {
                ...part,
                status: mode === "completed" ? "completed" : mode,
                data: { ...(part.data ?? {}), subagentData },
              }
            : part,
        ),
      });
    }
  }
}

function sayTypeForPart(part: MessagePart): ChatMessage["say"] {
  if (part.type === "reasoning") return "reasoning";
  if (part.type === "tool") return "tool";
  if (part.type === "tool_output") return "code_result";
  if (part.type === "code") return "code";
  if (part.type === "artifact") return "image";
  if (part.type === "plan") return "plan";
  if (part.type === "progress") return "progress";
  if (part.type === "error") return "error";
  return "text";
}

function messageFromNativePart(part: MessagePart): ChatMessage {
  const data = part.data ?? {};
  const step = typeof data.step === "number" ? data.step : data.stepNumber;
  return {
    ts: Date.now(),
    type: "say",
    say: sayTypeForPart(part),
    text: part.text ?? "",
    partial: part.status === "running" || part.status === "streaming",
    runId: part.runId || part.run_id,
    toolName: part.tool,
    toolCallId: part.callId || part.call_id,
    stepNumber: typeof step === "number" ? step : undefined,
    codeError: typeof data.error === "string" ? data.error : null,
    durationMs:
      typeof data.durationMs === "number"
        ? data.durationMs
        : typeof data.duration_ms === "number"
          ? data.duration_ms
          : undefined,
    progressStage: typeof data.stage === "string" ? data.stage : undefined,
    progressDetail: part.text || progressDetailFromData(data),
    parts: [part],
  };
}

export function createUserMessagePart({
  text,
  images,
  files,
  ts,
}: {
  text: string;
  images?: string[];
  files?: string[];
  ts: number;
}): MessagePart {
  return {
    id: `user:${ts}`,
    type: "text",
    status: "completed",
    text,
    data: {
      role: "user",
      images: images ?? [],
      files: files ?? [],
    },
    createdAt: ts,
  };
}

export function createSystemTextMessagePart(
  text: string,
  ts: number,
): MessagePart {
  return {
    id: `system:${ts}`,
    type: "text",
    status: "completed",
    text,
    data: { role: "system" },
    createdAt: ts,
  };
}

export function createErrorMessagePart(text: string, ts: number): MessagePart {
  return {
    id: `error:${ts}`,
    type: "error",
    status: "failed",
    text,
    createdAt: ts,
  };
}

export function upsertNativePartIntoConversation(
  conv: Conversation,
  part: MessagePart,
): Conversation {
  if (part.type === "turn") return conv;
  const messages = [...conv.messages];
  const existingIndex = messages.findIndex((message) =>
    message.parts?.some((candidate) => candidate.id === part.id),
  );
  if (existingIndex >= 0) {
    messages[existingIndex] = applyNativePartToMessage(
      upsertMessagePart(messages[existingIndex], part),
      part,
    );
    return { ...conv, messages, updatedAt: Date.now() };
  }
  messages.push(messageFromNativePart(part));
  return { ...conv, messages, updatedAt: Date.now() };
}

function isOpenPartStatus(status: MessagePart["status"] | undefined): boolean {
  return status === "pending" || status === "running" || status === "streaming";
}

function isTurnScopedPart(part: MessagePart): boolean {
  return (
    part.type === "text" ||
    part.type === "reasoning" ||
    part.type === "tool" ||
    part.type === "tool_output" ||
    part.type === "code" ||
    part.type === "plan" ||
    part.type === "progress"
  );
}

function settleMessagePartStatus(
  part: MessagePart,
  mode: "completed" | "failed" | "cancelled",
): MessagePart {
  if (!isTurnScopedPart(part)) return part;
  if (!isOpenPartStatus(part.status)) return part;
  return { ...part, status: mode };
}

function settledToolStatusForMode(
  current: ChatMessage["toolStatus"] | undefined,
  mode: "completed" | "failed" | "cancelled",
): ChatMessage["toolStatus"] | undefined {
  if (current !== "pending" && current !== "running") return current;
  return mode === "failed" || mode === "cancelled" ? "failed" : "completed";
}

export function settleConversationForTurnEnd(
  conv: Conversation,
  mode: "completed" | "failed" | "cancelled",
): Conversation {
  let changed = false;
  const messages = conv.messages.map((message) => {
    let next = message;
    if (message.parts?.length) {
      const parts = message.parts.map((part) => {
        const settled = settleMessagePartStatus(part, mode);
        if (settled !== part) changed = true;
        return settled;
      });
      next = { ...next, parts };
    }
    if (next.partial) {
      changed = true;
      next = { ...next, partial: false };
    }
    if (next.toolStatus === "pending" || next.toolStatus === "running") {
      changed = true;
      next = {
        ...next,
        toolStatus: settledToolStatusForMode(next.toolStatus, mode),
      };
    }
    return next;
  });
  return changed ? { ...conv, messages, updatedAt: Date.now() } : conv;
}

function applyNativePartToMessage(
  message: ChatMessage,
  part: MessagePart,
): ChatMessage {
  const mergedPart =
    message.parts?.find((candidate) => candidate.id === part.id) ?? part;
  const partial =
    mergedPart.status === "running" || mergedPart.status === "streaming";
  const data = mergedPart.data ?? {};
  const updates: Partial<ChatMessage> = {
    partial,
    runId: mergedPart.runId || mergedPart.run_id || message.runId,
  };
  if (mergedPart.type === "text") {
    updates.say = "text";
    if (mergedPart.text != null) updates.text = mergedPart.text || message.text;
  } else if (mergedPart.type === "reasoning") {
    updates.say = "reasoning";
    if (mergedPart.text != null) updates.text = mergedPart.text || message.text;
  } else if (mergedPart.type === "code") {
    updates.say = "code";
    const step = typeof data.step === "number" ? data.step : data.stepNumber;
    if (typeof step === "number") updates.stepNumber = step;
    if (mergedPart.text != null) updates.text = mergedPart.text || message.text;
  } else if (mergedPart.type === "tool") {
    updates.say = "tool";
    updates.toolName = mergedPart.tool || message.toolName;
    updates.toolCallId =
      mergedPart.callId || mergedPart.call_id || message.toolCallId;
    updates.toolStatus =
      mergedPart.status === "failed"
        ? "failed"
        : partial
          ? "running"
          : "completed";
  } else if (mergedPart.type === "tool_output") {
    updates.say = "code_result";
    if (mergedPart.text != null) updates.text = mergedPart.text || message.text;
    const step = typeof data.step === "number" ? data.step : data.stepNumber;
    if (typeof step === "number") updates.stepNumber = step;
    updates.codeError =
      typeof data.error === "string" ? data.error : message.codeError;
    updates.durationMs =
      typeof data.durationMs === "number"
        ? data.durationMs
        : typeof data.duration_ms === "number"
          ? data.duration_ms
          : message.durationMs;
  } else if (mergedPart.type === "progress") {
    updates.say = "progress";
    updates.progressStage =
      typeof data.stage === "string" ? data.stage : message.progressStage;
    updates.progressDetail =
      mergedPart.text || progressDetailFromData(data) || message.progressDetail;
  } else if (mergedPart.type === "artifact") {
    updates.say = "image";
    updates.text = mergedPart.text || message.text;
    if (Array.isArray(data.images)) updates.images = data.images as string[];
    if (Array.isArray(data.files)) updates.files = data.files as string[];
  } else if (mergedPart.type === "error") {
    updates.say = "error";
    updates.text = mergedPart.text || message.text;
  }
  return { ...message, ...updates };
}

function progressDetailFromData(
  data: Record<string, unknown>,
): string | undefined {
  if (typeof data.detail === "string") return data.detail;
  if (typeof data.message === "string") return data.message;
  return undefined;
}
