/** 文件职责：layers 前端功能：验证对应功能的行为与边界。 */
import { describe, expect, it } from "vitest";
import { normaliseHex } from "./styleControls";

describe("layer style controls", () => {
  it("normalizes supported color input for the native picker", () => {
    expect(normaliseHex("#ABC")).toBe("#aabbcc");
    expect(normaliseHex("#AABBCCDD")).toBe("#aabbcc");
    expect(normaliseHex("invalid")).toBe("#3b82f6");
  });
});
