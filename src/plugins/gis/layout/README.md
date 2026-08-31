# 布局编排功能阅读顺序

主入口是 `LayoutComposerView.tsx`，状态入口是 `model/layoutComposerStore.ts`。

| 位置 | 作用 |
| --- | --- |
| `LayoutComposerView.tsx` | 画布页面和工具栏的总编排 |
| `inspector` | 右侧属性检查器 |
| `elements` | 画布元素的外框和内容渲染 |
| `model` | 布局状态与类型 |
| `export` | 导出、图例、比例尺和地图快照 |
| `data` | 布局持久化 |
