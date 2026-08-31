/** 文件职责：layers 前端功能：可复用界面组件。 */
import { useEffect, useState } from "react";

// 与颜色输入组件共同维护，避免为十几行转换逻辑再拆一个文件。
// eslint-disable-next-line react-refresh/only-export-components
export function normaliseHex(color: string): string {
  if (/^#[0-9a-f]{6}$/i.test(color)) return color.toLowerCase();
  if (/^#[0-9a-f]{3}$/i.test(color)) {
    return `#${color
      .slice(1)
      .split("")
      .map((character) => character.repeat(2))
      .join("")
      .toLowerCase()}`;
  }
  if (/^#[0-9a-f]{8}$/i.test(color)) return color.slice(0, 7).toLowerCase();
  return "#3b82f6";
}

export function StyleRow({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className="flex items-center gap-2"
      draggable={false}
      onDragStart={(event) => {
        event.preventDefault();
        event.stopPropagation();
      }}
      onPointerDown={(event) => event.stopPropagation()}
      onMouseDown={(event) => event.stopPropagation()}
    >
      <span className="text-2xs text-text-muted w-12 shrink-0">{label}</span>
      <div className="flex-1 flex items-center gap-2 min-w-0">{children}</div>
    </div>
  );
}

export function StyleStateChip({
  label,
  value,
}: {
  label: string;
  value: string;
}) {
  return (
    <span
      className="inline-flex max-w-full items-center gap-1 rounded bg-bg-secondary px-1.5 py-0.5 text-[10px] leading-4 text-text-muted border-[0.5px] border-border/25"
      title={`${label}: ${value}`}
    >
      <span className="shrink-0 text-text-muted/70">{label}</span>
      <span className="truncate text-text-secondary">{value}</span>
    </span>
  );
}

/** Native color control shared by vector and raster style editors. */
export function ColorSwatch({
  color,
  onChange,
}: {
  color: string;
  onChange: (color: string) => void;
}) {
  return (
    <label
      className="w-5 h-5 rounded border border-border shrink-0 cursor-pointer relative overflow-hidden"
      style={{ backgroundColor: color }}
      title={color}
    >
      <input
        type="color"
        value={normaliseHex(color)}
        onChange={(event) => onChange(event.target.value)}
        className="absolute inset-0 opacity-0 cursor-pointer"
      />
    </label>
  );
}

export function HexInput({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  const [local, setLocal] = useState(value);

  useEffect(() => {
    setLocal(value);
  }, [value]);

  const commit = () => {
    if (/^#[0-9a-f]{3,8}$/i.test(local)) onChange(local);
    else setLocal(value);
  };

  return (
    <input
      type="text"
      value={local}
      onChange={(event) => setLocal(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === "Enter") {
          commit();
          event.currentTarget.blur();
        }
      }}
      className="w-16 bg-bg-tertiary text-2xs font-mono text-text-primary px-1.5 py-0.5 rounded outline-none border border-border focus:border-accent-primary"
    />
  );
}
