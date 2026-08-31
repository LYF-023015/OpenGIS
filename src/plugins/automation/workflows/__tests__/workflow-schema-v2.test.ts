/** 文件职责：workflows 前端功能：验证对应功能的行为与边界。 */
import { describe, expect, it } from "vitest";
import {
  createEmptyWorkflow,
  parseWorkflow,
  serialiseWorkflow,
  validateWorkflow,
} from "../model/workflow-schema";

describe("Workflow schema v2", () => {
  it("creates and round-trips a structured Agent node", () => {
    const workflow = createEmptyWorkflow("Learn GIS");
    workflow.nodes.push({
      id: "step_1",
      title: "Inspect data",
      type: "agent_task",
      execution: { kind: "agent_task", ref: "gis-build" },
      inputs: [],
      outputs: [],
      params: {},
      position: { x: 0, y: 0 },
      conditions: [
        { expression: { exists: { var: "output" } }, onFalse: "fail" },
      ],
      retryPolicy: { maxAttempts: 2, backoffMs: 100 },
    });
    const parsed = parseWorkflow(serialiseWorkflow(workflow));
    expect(parsed.schemaVersion).toBe(2);
    expect(parsed.nodes[0].execution).toEqual({
      kind: "agent_task",
      ref: "gis-build",
    });
  });

  it("requires explicit migration for v1", () => {
    expect(() =>
      parseWorkflow('{"schemaVersion":1,"nodes":[],"edges":[]}'),
    ).toThrow(/converted to v2/);
  });

  it("rejects Python and mismatched execution references", () => {
    const workflow = createEmptyWorkflow("Unsafe");
    workflow.nodes.push({
      id: "step",
      title: "Unsafe",
      type: "agent_task",
      execution: { kind: "agent_task", ref: "scripts/run.py" },
      inputs: [],
      outputs: [],
      params: {},
      position: { x: 0, y: 0 },
    });
    expect(() => validateWorkflow(workflow)).toThrow(/Python reference/);
    workflow.nodes[0].execution = { kind: "tool_call", ref: "read_file" };
    expect(() => validateWorkflow(workflow)).toThrow(
      /Invalid execution reference/,
    );
  });
});
