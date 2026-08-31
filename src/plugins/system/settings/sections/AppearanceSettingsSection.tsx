/** 文件职责：settings 前端功能：实现该文件名所对应的单一职责。 */
import { useT } from "@/app/i18n";
import { mapEngine } from "@/plugins/gis/map/engine/MapEngine";
import { useMapStore } from "@/plugins/gis/map/model/mapStore";
import { BUILTIN_BASEMAPS } from "@/shared/geo";
import type { AppearanceSettings } from "../model/settingsStore";
import {
  SettingCheckbox,
  SettingInput,
  SettingItem,
  SettingSection,
  SettingSelect,
} from "../components/SettingItem";

interface AppearanceSettingsSectionProps {
  value: AppearanceSettings;
  onChange: (updates: Partial<AppearanceSettings>) => void;
  sectionRef: (element: HTMLDivElement | null) => void;
}

export function AppearanceSettingsSection({
  value,
  onChange,
  sectionRef,
}: AppearanceSettingsSectionProps) {
  const t = useT();

  const setLabelsVisible = (visible: boolean) => {
    onChange({ showMapLabels: visible });
    const store = useMapStore.getState();
    store.setLabelsVisible(visible);
    const currentBasemap = store.basemap;
    if (currentBasemap.type === "raster-tiles") {
      const target = BUILTIN_BASEMAPS.find(
        (item) =>
          item.id === (visible ? "carto-voyager" : "carto-voyager-nolabels"),
      );
      if (target && target.id !== currentBasemap.id) store.setBasemap(target);
      return;
    }
    const alternateId = visible
      ? currentBasemap.id.replace("-nolabels", "")
      : `${currentBasemap.id}-nolabels`;
    const alternate = BUILTIN_BASEMAPS.find((item) => item.id === alternateId);
    if (alternate) {
      store.setBasemap(alternate);
      return;
    }
    const map = mapEngine.getMap();
    if (!map) return;
    if (map.isStyleLoaded()) mapEngine.setLabelsVisible(visible);
    else map.once("style.load", () => mapEngine.setLabelsVisible(visible));
  };

  return (
    <div id="section-appearance" ref={sectionRef}>
      <SettingSection title={t.settings.appearance}>
        <SettingItem
          id="appearance-theme"
          label={t.settings.theme}
          description={t.settings.themeDesc}
        >
          <SettingSelect
            id="appearance-theme"
            value={value.theme}
            onChange={(next) =>
              onChange({ theme: next as AppearanceSettings["theme"] })
            }
            options={[
              { value: "dark", label: t.settings.themeDark },
              { value: "light", label: t.settings.themeLight },
              { value: "system", label: t.settings.themeSystem },
            ]}
          />
        </SettingItem>
        <SettingItem
          id="appearance-language"
          label={t.settings.language}
          description={t.settings.languageDesc}
        >
          <SettingSelect
            id="appearance-language"
            value={value.language}
            onChange={(next) =>
              onChange({ language: next as AppearanceSettings["language"] })
            }
            options={[
              { value: "en", label: "English" },
              { value: "zh", label: "中文" },
            ]}
          />
        </SettingItem>
        <SettingItem
          id="appearance-basemap"
          label={t.settings.basemap}
          description={t.settings.basemapDesc}
        >
          <SettingSelect
            id="appearance-basemap"
            value={value.basemapId}
            onChange={(next) => {
              onChange({ basemapId: next });
              const basemap = BUILTIN_BASEMAPS.find((item) => item.id === next);
              if (basemap) useMapStore.getState().setBasemap(basemap);
            }}
            options={BUILTIN_BASEMAPS.map((item) => ({
              value: item.id,
              label: item.name,
            }))}
            className="min-w-[260px]"
          />
        </SettingItem>
        <SettingItem
          id="appearance-map-labels"
          label={t.settings.showMapLabels}
          description={t.settings.showMapLabelsDesc}
        >
          <SettingCheckbox
            id="appearance-map-labels"
            checked={value.showMapLabels}
            onChange={setLabelsVisible}
            label={t.settings.showMapLabels}
          />
        </SettingItem>
        <SettingItem
          id="appearance-custom-tile"
          label={t.settings.customTileUrl}
          description={t.settings.customTileUrlDesc}
        >
          <SettingInput
            id="appearance-custom-tile"
            value={value.customTileUrl}
            onChange={(next) => {
              onChange({ customTileUrl: next });
              if (next.trim())
                useMapStore.getState().setBasemap({
                  id: "custom",
                  name: "Custom Tiles",
                  type: "raster-tiles",
                  url: next.trim(),
                });
            }}
            placeholder="https://tile.example.com/{z}/{x}/{y}.png"
          />
        </SettingItem>
      </SettingSection>
    </div>
  );
}
