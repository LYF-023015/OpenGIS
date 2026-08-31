/** 文件职责：chat 前端功能：验证对应功能的行为与边界。 */
import { describe, expect, it } from "vitest";
import type { ChatMessage } from "@/plugins/assistant/chat/model/chatTypes";
import {
  messagePartsForRender,
  upsertMessagePart,
} from "@/plugins/assistant/chat/model/chatMessageParts";

describe("chatMessageParts", () => {
  it("renders only native MessagePart[] from the message envelope", () => {
    const message: ChatMessage = {
      ts: 1000,
      type: "say",
      say: "text",
      text: "native envelope text",
      parts: [
        {
          id: "native",
          type: "text",
          status: "completed",
          text: "native text",
          data: { source: "event-log" },
        },
      ],
    };

    expect(messagePartsForRender(message)).toEqual(message.parts);
  });

  it("does not synthesize fallback parts for message envelopes without parts", () => {
    const message: ChatMessage = {
      ts: 2000,
      type: "say",
      say: "text",
      text: "envelope text",
      partial: true,
    };

    expect(messagePartsForRender(message)).toEqual([]);
  });

  it("appends explicit delta streaming parts by stable id", () => {
    const message: ChatMessage = {
      ts: 4000,
      type: "say",
      say: "text",
      text: "",
    };

    const first = upsertMessagePart(message, {
      id: "run:text:final",
      type: "text",
      status: "streaming",
      text: "hel",
      data: { text_mode: "delta" },
    });
    const second = upsertMessagePart(first, {
      id: "run:text:final",
      type: "text",
      status: "streaming",
      text: "lo",
      data: { text_mode: "delta" },
    });
    const done = upsertMessagePart(second, {
      id: "run:text:final",
      type: "text",
      status: "completed",
      data: { finished: true },
    });

    expect(messagePartsForRender(done)).toHaveLength(1);
    expect(messagePartsForRender(done)[0]).toMatchObject({
      id: "run:text:final",
      type: "text",
      status: "completed",
      text: "hello",
      data: { text_mode: "delta", finished: true },
    });
  });

  it("replaces cumulative streaming snapshots instead of repeating them", () => {
    const message: ChatMessage = {
      ts: 4500,
      type: "say",
      say: "text",
      text: "",
    };

    const first = upsertMessagePart(message, {
      id: "run:text:final",
      type: "text",
      status: "streaming",
      text: "回顾",
      data: { text_mode: "snapshot" },
    });
    const second = upsertMessagePart(first, {
      id: "run:text:final",
      type: "text",
      status: "streaming",
      text: "回顾一下",
      data: { text_mode: "snapshot" },
    });
    const third = upsertMessagePart(second, {
      id: "run:text:final",
      type: "text",
      status: "streaming",
      text: "回顾一下对话",
      data: { text_mode: "snapshot" },
    });

    expect(messagePartsForRender(third)).toHaveLength(1);
    expect(messagePartsForRender(third)[0].text).toBe("回顾一下对话");
  });

  it("recognizes legacy cumulative snapshots without a text mode marker", () => {
    const message: ChatMessage = {
      ts: 4600,
      type: "say",
      say: "text",
      text: "",
    };

    const first = upsertMessagePart(message, {
      id: "run:text:final",
      type: "text",
      status: "streaming",
      text: "hello",
    });
    const second = upsertMessagePart(first, {
      id: "run:text:final",
      type: "text",
      status: "streaming",
      text: "hello world",
    });

    expect(messagePartsForRender(second)[0].text).toBe("hello world");
  });

  it("keeps reasoning and text as separate native parts", () => {
    const message: ChatMessage = {
      ts: 5000,
      type: "say",
      say: "reasoning",
      text: "",
    };

    const withReasoning = upsertMessagePart(message, {
      id: "run:reasoning:1",
      type: "reasoning",
      status: "streaming",
      text: "reasoning summary",
    });
    const withText = upsertMessagePart(withReasoning, {
      id: "run:text:final",
      type: "text",
      status: "streaming",
      text: "final answer",
    });

    expect(messagePartsForRender(withText)).toHaveLength(2);
    expect(messagePartsForRender(withText)[0]).toMatchObject({
      id: "run:reasoning:1",
      type: "reasoning",
      text: "reasoning summary",
    });
    expect(messagePartsForRender(withText)[1]).toMatchObject({
      id: "run:text:final",
      type: "text",
      text: "final answer",
    });
  });

  it("keeps artifact image data in native parts", () => {
    const message: ChatMessage = {
      ts: 6000,
      type: "say",
      say: "image",
      text: "",
    };

    const withArtifact = upsertMessagePart(message, {
      id: "run:artifact:image:plot.png",
      type: "artifact",
      status: "completed",
      text: "Plot",
      data: {
        kind: "image",
        images: ["/tmp/plot.png"],
        files: ["/tmp/plot.png"],
      },
    });

    expect(messagePartsForRender(withArtifact)).toHaveLength(1);
    expect(messagePartsForRender(withArtifact)[0]).toMatchObject({
      type: "artifact",
      text: "Plot",
      data: {
        kind: "image",
        images: ["/tmp/plot.png"],
        files: ["/tmp/plot.png"],
      },
    });
  });
});
