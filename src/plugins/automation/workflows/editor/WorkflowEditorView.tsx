/** 文件职责：workflows 前端功能：页面级界面与交互编排。 */
/**
 * WorkflowEditorView — Guided step-based workflow editor.
 *
 * Instead of a free-form canvas with nodes and wires, this editor
 * presents a linear step list that the user fills in sequentially.
 * Each step has:
 *   - A title (what this step does)
 *   - A description (detailed instructions for the LLM)
 *   - Input/output contracts (what this step receives and hands off)
 *   - Safe JSON conditions (never executable source code)
 *   - Optional params (key/value config passed to the step)
 *
 * The workflow is executed by the Java Workflow v2 engine through
 * structured Agent, tool, operation, Java, or sub-workflow references.
 *
 * Design: Guided / wizard-style UI that makes it easy for non-
 * programmers to define repeatable GIS pipelines.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Plus,
  Save,
  Play,
  GitBranch,
  AlertCircle,
  ArrowDown,
} from "lucide-react";
import type { ViewTab } from "@/shell/model/viewStore";
import { useWorkflowStore } from "@/plugins/automation/workflows/model/workflowStore";
import { useChatStore } from "@/plugins/assistant/chat/model/chatStore";
import { useAssetStore } from "@/plugins/workspace/assets/model/assetStore";
import { backendClient } from "@/shared/backend/backendClient";
import { useT } from "@/app/i18n";
import type { WorkflowNode } from "@/plugins/automation/workflows/model/workflow-schema";
import {
  EmptyWorkflowState,
  StepCard,
  StepInspector,
  ToolbarButton,
  WorkflowOverview,
} from "./WorkflowEditorPanels";

// ─── Top-level component ─────────────────────────────────────────

interface WorkflowEditorViewProps {
  tab: ViewTab;
}

export function WorkflowEditorView({ tab }: WorkflowEditorViewProps) {
  const path = tab.filePath!;
  const loadWorkflow = useWorkflowStore((s) => s.loadWorkflow);
  const saveWorkflow = useWorkflowStore((s) => s.saveWorkflow);
  const loaded = useWorkflowStore((s) => s.loaded[path]);

  const [loadError, setLoadError] = useState<string | null>(null);

  // ── Load workflow on mount / path change ──────────────────────
  useEffect(() => {
    let cancelled = false;
    setLoadError(null);
    loadWorkflow(path).then((doc) => {
      if (cancelled) return;
      if (!doc) {
        setLoadError(
          useWorkflowStore.getState().error || "Failed to load workflow",
        );
      }
    });
    return () => {
      cancelled = true;
    };
  }, [path, loadWorkflow]);

  // ── Ctrl+S / Cmd+S saves ──────────────────────────────────────
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        saveWorkflow(path);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [path, saveWorkflow]);

  if (loadError) {
    return (
      <div className="w-full h-full flex items-center justify-center bg-bg-primary">
        <div className="text-center max-w-sm">
          <AlertCircle className="w-10 h-10 text-accent-danger/60 mx-auto mb-3" />
          <p className="text-sm text-text-secondary mb-1">
            Couldn't open this workflow
          </p>
          <p className="text-xs text-text-muted">{loadError}</p>
        </div>
      </div>
    );
  }

  if (!loaded) {
    return (
      <div className="w-full h-full flex items-center justify-center bg-bg-primary">
        <p className="text-xs text-text-muted">Loading workflow…</p>
      </div>
    );
  }

  return <GuidedWorkflowEditor path={path} />;
}

// ─── Guided Workflow Editor ──────────────────────────────────────

function GuidedWorkflowEditor({ path }: { path: string }) {
  const t = useT();
  const loaded = useWorkflowStore((s) => s.loaded[path]);
  const addNode = useWorkflowStore((s) => s.addNode);
  const updateNode = useWorkflowStore((s) => s.updateNode);
  const removeNode = useWorkflowStore((s) => s.removeNode);
  const addEdge = useWorkflowStore((s) => s.addEdge);
  const removeEdge = useWorkflowStore((s) => s.removeEdge);
  const saveWorkflow = useWorkflowStore((s) => s.saveWorkflow);
  const updateLoaded = useWorkflowStore((s) => s.updateLoaded);

  const doc = loaded!.doc;
  const isDirty = loaded!.dirty;

  const [selectedStepId, setSelectedStepId] = useState<string | null>(null);
  const [isRunning, setIsRunning] = useState(false);

  // ── Drag-and-drop state ───────────────────────────────────────
  const [draggedStepId, setDraggedStepId] = useState<string | null>(null);
  const [dragOverStepId, setDragOverStepId] = useState<string | null>(null);
  const [dragOverPosition, setDragOverPosition] = useState<
    "above" | "below" | null
  >(null);

  // Steps are nodes in order (edges define the sequence)
  const steps = useMemo(() => {
    // Build adjacency from edges
    const nextMap = new Map<string, string>();
    const prevMap = new Map<string, string>();
    for (const edge of doc.edges) {
      nextMap.set(edge.source, edge.target);
      prevMap.set(edge.target, edge.source);
    }

    // Find the first node (no incoming edge)
    const startNodes = doc.nodes.filter((n) => !prevMap.has(n.id));

    if (startNodes.length === 0 && doc.nodes.length > 0) {
      // Fallback: just return nodes in array order
      return doc.nodes;
    }

    // Walk the chain from the first start node
    const ordered: WorkflowNode[] = [];
    const visited = new Set<string>();
    let current = startNodes[0]?.id;

    while (current && !visited.has(current)) {
      visited.add(current);
      const node = doc.nodes.find((n) => n.id === current);
      if (node) ordered.push(node);
      current = nextMap.get(current) || "";
    }

    // Add any unlinked nodes at the end
    for (const node of doc.nodes) {
      if (!visited.has(node.id)) {
        ordered.push(node);
      }
    }

    return ordered;
  }, [doc.nodes, doc.edges]);

  const selectedStep = selectedStepId
    ? steps.find((s) => s.id === selectedStepId) || null
    : null;

  // ── Add a new step ────────────────────────────────────────────
  const handleAddStep = useCallback(() => {
    const stepNum = steps.length + 1;
    const newNode: WorkflowNode = {
      id: `step_${Date.now().toString(36)}`,
      title: `Step ${stepNum}`,
      description: "",
      inputContract: "",
      outputContract: "",
      type: "agent_task",
      execution: { kind: "agent_task", ref: "gis-build" },
      inputs: [],
      outputs: [],
      params: {},
      position: { x: 0, y: stepNum * 100 },
      conditions: [],
      retryPolicy: { maxAttempts: 1, backoffMs: 0 },
    };

    addNode(path, newNode);

    // Auto-link: connect the last step to this new one
    if (steps.length > 0) {
      const lastStep = steps[steps.length - 1];
      addEdge(path, {
        id: `edge_${Date.now().toString(36)}`,
        source: lastStep.id,
        sourceHandle: "output",
        target: newNode.id,
        targetHandle: "input",
      });
    }

    setSelectedStepId(newNode.id);
  }, [path, steps, addNode, addEdge]);

  // ── Remove a step ─────────────────────────────────────────────
  const handleRemoveStep = useCallback(
    (stepId: string) => {
      const stepIndex = steps.findIndex((s) => s.id === stepId);

      // Re-link: connect prev → next to maintain the chain
      if (stepIndex > 0 && stepIndex < steps.length - 1) {
        const prev = steps[stepIndex - 1];
        const next = steps[stepIndex + 1];
        addEdge(path, {
          id: `edge_relink_${Date.now().toString(36)}`,
          source: prev.id,
          sourceHandle: "output",
          target: next.id,
          targetHandle: "input",
        });
      }

      removeNode(path, stepId);
      if (selectedStepId === stepId) {
        setSelectedStepId(null);
      }
    },
    [path, steps, selectedStepId, removeNode, addEdge],
  );

  // ── Reorder steps (used by both buttons and drag-drop) ────────
  const reorderSteps = useCallback(
    (newOrder: WorkflowNode[]) => {
      // Remove all existing sequential edges
      const edgeIds = doc.edges.map((e) => e.id);
      for (const eid of edgeIds) {
        removeEdge(path, eid);
      }

      // Re-add edges in new order
      for (let i = 0; i < newOrder.length - 1; i++) {
        addEdge(path, {
          id: `edge_${Date.now().toString(36)}_${i}`,
          source: newOrder[i].id,
          sourceHandle: "output",
          target: newOrder[i + 1].id,
          targetHandle: "input",
        });
      }
    },
    [path, doc.edges, removeEdge, addEdge],
  );

  // ── Drag-and-drop handlers ────────────────────────────────────
  const handleDragStart = useCallback((e: React.DragEvent, stepId: string) => {
    setDraggedStepId(stepId);
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", stepId);
    // Make the drag image slightly transparent
    if (e.currentTarget instanceof HTMLElement) {
      e.currentTarget.style.opacity = "0.5";
    }
  }, []);

  const handleDragEnd = useCallback((e: React.DragEvent) => {
    if (e.currentTarget instanceof HTMLElement) {
      e.currentTarget.style.opacity = "1";
    }
    setDraggedStepId(null);
    setDragOverStepId(null);
    setDragOverPosition(null);
  }, []);

  const handleDragOver = useCallback(
    (e: React.DragEvent, stepId: string) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
      if (stepId === draggedStepId) return;

      // Determine if dropping above or below
      const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
      const midY = rect.top + rect.height / 2;
      const position = e.clientY < midY ? "above" : "below";

      setDragOverStepId(stepId);
      setDragOverPosition(position);
    },
    [draggedStepId],
  );

  const handleDragLeave = useCallback(() => {
    setDragOverStepId(null);
    setDragOverPosition(null);
  }, []);

  const handleDrop = useCallback(
    (e: React.DragEvent, targetStepId: string) => {
      e.preventDefault();
      if (!draggedStepId || draggedStepId === targetStepId) return;

      const fromIdx = steps.findIndex((s) => s.id === draggedStepId);
      const toIdx = steps.findIndex((s) => s.id === targetStepId);
      if (fromIdx < 0 || toIdx < 0) return;

      const newOrder = [...steps];
      const [moved] = newOrder.splice(fromIdx, 1);

      // Calculate insert position based on drag position
      let insertIdx = toIdx;
      if (fromIdx < toIdx) insertIdx--; // Adjust for removal
      if (dragOverPosition === "below") insertIdx++;

      newOrder.splice(insertIdx, 0, moved);
      reorderSteps(newOrder);

      setDraggedStepId(null);
      setDragOverStepId(null);
      setDragOverPosition(null);
    },
    [draggedStepId, dragOverPosition, steps, reorderSteps],
  );

  // ── Duplicate step ────────────────────────────────────────────
  const handleDuplicateStep = useCallback(
    (stepId: string) => {
      const step = steps.find((s) => s.id === stepId);
      if (!step) return;

      const newNode: WorkflowNode = {
        ...step,
        id: `step_${Date.now().toString(36)}`,
        title: `${step.title} ${t.workflow.editor.copySuffix}`,
        position: { x: step.position.x, y: step.position.y + 100 },
      };

      addNode(path, newNode);

      // Link after the original step
      const stepIdx = steps.findIndex((s) => s.id === stepId);
      if (stepIdx < steps.length - 1) {
        // Remove edge from original → next
        const nextStep = steps[stepIdx + 1];
        const edgeToRemove = doc.edges.find(
          (e) => e.source === stepId && e.target === nextStep.id,
        );
        if (edgeToRemove) removeEdge(path, edgeToRemove.id);

        // Add: original → copy → next
        addEdge(path, {
          id: `edge_dup1_${Date.now().toString(36)}`,
          source: stepId,
          sourceHandle: "output",
          target: newNode.id,
          targetHandle: "input",
        });
        addEdge(path, {
          id: `edge_dup2_${Date.now().toString(36)}`,
          source: newNode.id,
          sourceHandle: "output",
          target: nextStep.id,
          targetHandle: "input",
        });
      } else {
        // Append at end
        addEdge(path, {
          id: `edge_dup_${Date.now().toString(36)}`,
          source: stepId,
          sourceHandle: "output",
          target: newNode.id,
          targetHandle: "input",
        });
      }

      setSelectedStepId(newNode.id);
    },
    [
      path,
      steps,
      doc.edges,
      addNode,
      addEdge,
      removeEdge,
      t.workflow.editor.copySuffix,
    ],
  );

  // ── Run workflow ──────────────────────────────────────────────
  const handleRun = useCallback(async () => {
    if (isRunning) return;

    // Save first if dirty
    if (isDirty) {
      await saveWorkflow(path);
    }

    setIsRunning(true);
    try {
      const workspacePath = useAssetStore.getState().workspacePath;
      if (!workspacePath)
        throw new Error("Open a workspace before running a workflow");
      const chat = useChatStore.getState();
      const conversationId =
        chat.activeConversationId ?? chat.createConversation();
      await backendClient.send("rpc.workflow.save", {
        workspace_path: workspacePath,
        workflow: doc,
      });
      await backendClient.send("rpc.workflow.run", {
        workspace_path: workspacePath,
        workflow_id: doc.id,
        conversation_id: conversationId,
      });
    } catch (err) {
      console.error("Failed to run workflow:", err);
    } finally {
      setIsRunning(false);
    }
  }, [isRunning, isDirty, path, doc, saveWorkflow]);

  return (
    <div className="w-full h-full flex bg-bg-primary">
      {/* Left: Step list */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Toolbar */}
        <div className="h-10 border-b border-border flex items-center px-3 gap-2 shrink-0 bg-bg-secondary">
          <GitBranch className="w-4 h-4 text-accent-geo shrink-0" />
          <span className="text-xs font-medium text-text-primary truncate">
            {doc.name}
            {isDirty && <span className="text-accent-warning ml-1">●</span>}
          </span>

          <div className="flex-1" />

          <span className="text-2xs text-text-muted">
            {steps.length}{" "}
            {steps.length !== 1 ? t.workflow.steps : t.workflow.step}
          </span>

          <ToolbarButton
            icon={<Plus className="w-3.5 h-3.5" />}
            label={t.workflow.addStep}
            onClick={handleAddStep}
          />
          <ToolbarButton
            icon={<Save className="w-3.5 h-3.5" />}
            label={isDirty ? t.workflow.saveShortcut : t.workflow.saved}
            onClick={() => saveWorkflow(path)}
            disabled={!isDirty}
            accent={isDirty}
          />
          <ToolbarButton
            icon={<Play className="w-3.5 h-3.5" />}
            label={isRunning ? t.common.running : t.common.run}
            onClick={handleRun}
            disabled={steps.length === 0 || isRunning}
            accent={!isRunning && steps.length > 0}
          />
        </div>

        {/* Step list */}
        <div className="flex-1 overflow-y-auto scrollbar-thin">
          {steps.length === 0 ? (
            <EmptyWorkflowState onAddStep={handleAddStep} />
          ) : (
            <div className="p-4 space-y-1">
              {/* Workflow description */}
              <div className="mb-4 px-3">
                <input
                  value={doc.name}
                  onChange={(e) => updateLoaded(path, { name: e.target.value })}
                  className="w-full text-base font-semibold text-text-primary bg-transparent outline-none border-b border-transparent hover:border-border focus:border-accent-primary transition-colors pb-1"
                  placeholder="Workflow name..."
                />
                <textarea
                  value={doc.description ?? ""}
                  onChange={(e) =>
                    updateLoaded(path, { description: e.target.value })
                  }
                  placeholder="Describe what this workflow does..."
                  rows={2}
                  className="w-full mt-2 text-xs text-text-secondary bg-transparent outline-none border-b border-transparent hover:border-border focus:border-accent-primary transition-colors resize-none leading-relaxed"
                />
              </div>

              {/* Steps */}
              {steps.map((step, index) => (
                <div
                  key={step.id}
                  draggable
                  onDragStart={(e) => handleDragStart(e, step.id)}
                  onDragEnd={handleDragEnd}
                  onDragOver={(e) => handleDragOver(e, step.id)}
                  onDragLeave={handleDragLeave}
                  onDrop={(e) => handleDrop(e, step.id)}
                  className={`
                    transition-transform duration-150
                    ${draggedStepId === step.id ? "opacity-50" : ""}
                    ${dragOverStepId === step.id && dragOverPosition === "above" ? "border-t-2 border-t-accent-primary pt-1" : ""}
                    ${dragOverStepId === step.id && dragOverPosition === "below" ? "border-b-2 border-b-accent-primary pb-1" : ""}
                  `}
                >
                  <StepCard
                    step={step}
                    index={index}
                    totalSteps={steps.length}
                    isSelected={step.id === selectedStepId}
                    isDragging={draggedStepId === step.id}
                    onClick={() =>
                      setSelectedStepId(
                        selectedStepId === step.id ? null : step.id,
                      )
                    }
                    onRemove={() => handleRemoveStep(step.id)}
                    onDuplicate={() => handleDuplicateStep(step.id)}
                  />
                  {/* Connector arrow between steps */}
                  {index < steps.length - 1 && !draggedStepId && (
                    <div className="flex justify-center py-0.5">
                      <ArrowDown className="w-3.5 h-3.5 text-border" />
                    </div>
                  )}
                </div>
              ))}

              {/* Add step button at bottom */}
              <div className="pt-2 flex justify-center">
                <button
                  onClick={handleAddStep}
                  className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-text-muted hover:text-accent-primary border border-dashed border-border hover:border-accent-primary/50 rounded-lg transition-colors"
                >
                  <Plus className="w-3 h-3" />
                  {t.workflow.addStep}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Right: Step detail inspector */}
      <div className="w-[300px] shrink-0 border-l border-border bg-bg-secondary flex flex-col">
        {selectedStep ? (
          <StepInspector
            step={selectedStep}
            stepIndex={steps.findIndex((s) => s.id === selectedStep.id)}
            totalSteps={steps.length}
            onUpdate={(patch) => updateNode(path, selectedStep.id, patch)}
            onRemove={() => handleRemoveStep(selectedStep.id)}
          />
        ) : (
          <WorkflowOverview
            path={path}
            doc={doc}
            steps={steps}
            onUpdateDoc={(patch) => updateLoaded(path, patch)}
          />
        )}
      </div>
    </div>
  );
}
