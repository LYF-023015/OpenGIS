/** 文件职责：settings 前端功能：实现该文件名所对应的单一职责。 */
import { useT } from "@/app/i18n";
import { backendClient } from "@/shared/backend/backendClient";
import type { AgentSettings } from "../model/settingsStore";
import {
  SettingCheckbox,
  SettingItem,
  SettingNumber,
  SettingSection,
  SettingSlider,
  SettingTextArea,
} from "../components/SettingItem";

interface AgentSettingsSectionProps {
  value: AgentSettings;
  onChange: (updates: Partial<AgentSettings>) => void;
  sectionRef: (element: HTMLDivElement | null) => void;
}

export function AgentSettingsSection({
  value,
  onChange,
  sectionRef,
}: AgentSettingsSectionProps) {
  const t = useT();

  return (
    <div id="section-agent" ref={sectionRef}>
      <SettingSection title={t.settings.agentBehavior}>
        <SettingItem
          id="agent-maxmistakes"
          label={t.settings.maxConsecutiveMistakes}
          description={t.settings.maxConsecutiveMistakesDesc}
        >
          <SettingSlider
            id="agent-maxmistakes"
            value={value.maxConsecutiveMistakes}
            onChange={(next) => onChange({ maxConsecutiveMistakes: next })}
            min={1}
            max={10}
            step={1}
          />
        </SettingItem>
        <SettingItem
          id="agent-timeout"
          label={t.settings.timeout}
          description={t.settings.timeoutDesc}
        >
          <SettingNumber
            id="agent-timeout"
            value={value.codeExecutionTimeout}
            onChange={(next) => onChange({ codeExecutionTimeout: next })}
            min={10}
            max={600}
            step={10}
          />
        </SettingItem>
        <SettingItem
          id="agent-confirm"
          label={t.settings.requireConfirmation}
          description={t.settings.requireConfirmationDesc}
        >
          <SettingCheckbox
            id="agent-confirm"
            checked={value.requireConfirmation}
            onChange={(next) => onChange({ requireConfirmation: next })}
          />
        </SettingItem>
        <SettingItem
          id="agent-autorender"
          label={t.settings.autoRenderResults}
          description={t.settings.autoRenderResultsDesc}
        >
          <SettingCheckbox
            id="agent-autorender"
            checked={value.autoRenderResults}
            onChange={(next) => onChange({ autoRenderResults: next })}
          />
        </SettingItem>
        <SettingItem
          id="agent-condense"
          label={t.settings.autoCondenseContext}
          description={t.settings.autoCondenseContextDesc}
        >
          <SettingCheckbox
            id="agent-condense"
            checked={value.useAutoCondense}
            onChange={(next) => onChange({ useAutoCondense: next })}
          />
        </SettingItem>
        <SettingItem
          id="agent-debug"
          label={t.settings.debugMode}
          description={t.settings.debugModeDesc}
        >
          <SettingCheckbox
            id="agent-debug"
            checked={value.debugMode}
            onChange={(next) => {
              onChange({ debugMode: next });
              backendClient
                .send("rpc.debug.set_log_level", {
                  level: next ? "DEBUG" : "INFO",
                })
                .catch(() => {});
            }}
          />
        </SettingItem>
      </SettingSection>

      <SettingSection title={t.settings.customInstructions}>
        <SettingItem
          id="agent-instructions"
          label={t.settings.customInstructions}
          description={t.settings.customInstructionsDesc}
        >
          <SettingTextArea
            id="agent-instructions"
            value={value.customInstructions}
            onChange={async (next) => {
              const trimmed = next.slice(0, 2000);
              onChange({ customInstructions: trimmed });
              try {
                await backendClient.send("user_instructions.set", {
                  content: trimmed,
                });
              } catch (error) {
                console.warn("[Settings] user_instructions.set failed:", error);
              }
            }}
            placeholder="[user] Default to Chinese.&#10;[user] Use CGCS2000 (EPSG:4490).&#10;[agent] User prefers seaborn for charts."
            rows={8}
          />
          <div className="text-[10px] text-text-muted mt-1 text-right">
            {value.customInstructions.length} / 2000
          </div>
        </SettingItem>
      </SettingSection>
    </div>
  );
}
