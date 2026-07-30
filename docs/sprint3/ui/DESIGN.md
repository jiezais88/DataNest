# DataNest DESIGN.md — Sprint 3 DAG 编排与 SQL 任务编辑器

> **设计方向**：现代企业亮色风格（参考 Stripe + Vercel 精品质感）
> **AI 消费**：本文档供 Cursor / Claude Code / Copilot 等 AI 编程代理直接解析
> **版本**：v1.3 | **日期**：2026-07-29
> **继承说明**：本规范继承 Sprint 2 全部 design token，补充 Sprint 3 新增组件模式

---

## 1. Visual Theme & Atmosphere

**设计哲学**：专业可信赖的企业级数据中台。界面应当传达"这里是数据管理和分析的可信场所"——整洁、有条理、不放纵视觉，让数据内容本身成为主角。

**视觉基调**：现代 SaaS 产品，精致不浮夸，克制有品质。

**核心关键词**：专业 · 克制 · 精致阴影 · 清晰层级 · 信赖感

**光影与质感**：纯扁平 + 微阴影层次。卡片和输入框用极细边框（1-1.5px）定义边界，多层 box-shadow 构建 z 轴深度。弹窗遮罩加
`backdrop-filter: blur(2px)` 制造焦点分离。避免毛玻璃滥用，仅在遮罩层使用。

---

## 2. Color Palette & Roles

### 2.1 主色系统

```css
:root {
    /* ══ Background Layers ══ */
    --color-bg-root: #f7f8fa; /* 页面底色 */
    --color-bg-surface: #ffffff; /* 卡片/表格/弹窗背景 */
    --color-bg-elevated: #ffffff; /* 悬浮元素背景（与 surface 相同保持干净） */
    --color-bg-hover: #f1f3f6; /* 表格行 hover / 导航项 hover */

    /* ══ Borders ══ */
    --color-border-subtle: #e2e6ed; /* 卡片边框 / 输入框默认边框 */
    --color-border-strong: #cdd3dc; /* hover 状态边框 / 强调分割 */

    /* ══ Text ══ */
    --color-text-primary: #0f172a; /* 标题 / 正文 */
    --color-text-secondary: #475569; /* 辅助文字 / 标签 */
    --color-text-muted: #94a3b8; /* placeholder / 次要信息 / 禁用态 */

    /* ══ Accent — Indigo ══ */
    --color-accent: #4f46e5; /* 主按钮 / 链接 / 活跃态 */
    --color-accent-hover: #4338ca; /* 悬停加深 */
    --color-accent-light: #eef2ff; /* 标签背景 / 选中态底色 */
    --color-accent-glow: rgba(79, 70, 229, 0.12); /* focus ring */

    /* ══ Semantic ══ */
    --color-danger: #dc2626; /* 删除 / 禁用 / 错误 */
    --color-danger-hover: #b91c1c;
    --color-danger-light: #fef2f2; /* 错误提示背景 */

    --color-success: #16a34a; /* 成功 / 正常状态 */
    --color-success-light: #f0fdf4;

    --color-warning: #d97706; /* 警告 */
    --color-warning-light: #fffbeb;

    /* ══ DAG Node Status ══ */
    --color-node-waiting: #94a3b8;
    --color-node-running: #4f46e5;
    --color-node-success: #16a34a;
    --color-node-failed: #dc2626;
    --color-node-skipped: #d97706;

    /* ══ Shadows (完整 rgba 值) ══ */
    --color-shadow-xs: rgba(0, 0, 0, 0.04);
    --color-shadow-sm: rgba(0, 0, 0, 0.06);
    --color-shadow-md: rgba(0, 0, 0, 0.05);
    --color-shadow-lg: rgba(0, 0, 0, 0.06);
}
```

### 2.2 使用场景速查

| CSS 变量                | 使用位置                                   |
|-------------------------|--------------------------------------------|
| `--color-bg-root`       | `<body>` 底色、输入框背景、表头背景        |
| `--color-bg-surface`    | 登录卡片、表格容器、弹窗、工具栏卡片       |
| `--color-bg-hover`      | 表格行 hover、导航项 hover、操作按钮 hover |
| `--color-border-subtle` | 卡片边框、输入框默认边框、分割线           |
| `--color-accent`        | 主按钮、链接文字、活跃导航项、focus 边框   |
| `--color-accent-light`  | 角色标签背景、选中 chip 背景               |
| `--color-danger`        | 删除按钮、禁用状态标签、错误文字           |
| `--color-success`       | 正常状态标签、成功提示                     |
| `--color-node-running`  | DAG 节点运行中边框/状态指示                |
| `--color-node-skipped`  | DAG 节点被跳过边框/状态指示                |

---

## 3. Typography Rules

### 3.1 Font Family

```css
--font-sans: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
```

- **Inter** 从 Google Fonts 加载（weights: 400, 500, 600, 700, 800）
- 回退栈：系统原生字体，确保无网络时的可用性
- 不使用等宽字体作为 UI 字体（代码编辑器场景另配 `--font-mono`）

### 3.2 Type Scale

| 层级            | Font Size      | Font Weight | Line Height | Letter Spacing | 使用场景                  |
|-----------------|----------------|-------------|-------------|----------------|---------------------------|
| **Display**     | 24px/1.5rem    | 800         | 1.3         | -0.5px         | 页面主标题 `.page-title`  |
| **Heading**     | 20px/1.25rem   | 700         | 1.35        | -0.3px         | 弹窗标题 `.modal-title`   |
| **Subheading**  | 17px/1.0625rem | 700         | 1.4         | 0              | 确认对话框标题            |
| **Body**        | 14px/0.875rem  | 400         | 1.6         | 0              | 正文、表格内容、输入框    |
| **Body Strong** | 14px/0.875rem  | 600         | 1.6         | 0              | 表格用户名、按钮文字      |
| **Small**       | 13px/0.8125rem | 500         | 1.5         | 0              | 导航项、标签、工具栏      |
| **Caption**     | 12px/0.75rem   | 600         | 1.4         | 0.6px          | 表头（uppercase）、badge  |
| **Nano**        | 11px/0.6875rem | 600         | 1.4         | 1px            | 导航分组标题（uppercase） |

### 3.3 设计哲学

- **字重层次分明**：800 → 700 → 600 → 400 四级跳跃，用字重而非字号区分层级
- **标题收紧字距**：Display 和 Heading 使用负 letter-spacing，增加精致感
- **表头用大写 + 宽字距**：增强数据表格的扫描效率
- **最小字号 11px**：不做小于 11px 的文字，保证可读性

---

## 4. Component Stylings

### 4.1 Buttons

```css
/* ══ Primary Button ══ */
.btn-primary {
    background: var(--color-accent); /* #4f46e5 */
    color: #ffffff;
    padding: 12px 24px;
    border: none;
    border-radius: 8px; /* --radius-sm */
    font-size: 14px;
    font-weight: 600;
    letter-spacing: -0.1px;
    cursor: pointer;
    transition: all 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.btn-primary:hover {
    background: var(--color-accent-hover); /* #4338ca */
    box-shadow: var(--shadow-md);
}

.btn-primary:disabled {
    opacity: 0.45;
    cursor: not-allowed;
    box-shadow: none;
}

/* ══ Outline Button (用于"取消") ══ */
.btn-outline {
    background: transparent;
    color: var(--color-text-secondary); /* #475569 */
    border: 1.5px solid var(--color-border-subtle);
    padding: 9px 18px; /* btn-sm 尺寸 */
    border-radius: 8px;
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;
    transition: all 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.btn-outline:hover {
    border-color: var(--color-border-strong);
    color: var(--color-text-primary);
}

/* ══ Ghost Buttons (表格操作) ══ */
.btn-ghost {
    background: transparent;
    color: var(--color-text-muted); /* #94a3b8 */
    border: none;
    padding: 6px 14px;
    border-radius: 8px;
    font-size: 12px;
    font-weight: 500;
    cursor: pointer;
    transition: all 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.btn-ghost:hover {
    background: var(--color-bg-hover); /* #f1f3f6 */
    color: var(--color-text-primary);
}

.btn-ghost.danger:hover {
    background: var(--color-danger-light); /* #fef2f2 */
    color: var(--color-danger);
}
```

### 4.2 Cards

```css
.card {
    background: var(--color-bg-surface); /* #ffffff */
    border: 1px solid var(--color-border-subtle);
    border-radius: 16px; /* --radius-lg */
    padding: 48px 40px; /* 登录卡片 */
    box-shadow: 0 20px 25px rgba(0, 0, 0, 0.06),
    0 10px 10px rgba(0, 0, 0, 0.03); /* --shadow-xl */
}

.card-compact {
    /* 紧凑卡片（工具栏、小面板） */
    padding: 12px 16px;
    border-radius: 12px; /* --radius-md */
    box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04); /* --shadow-xs */
}
```

### 4.3 Inputs

```css
.form-input {
    width: 100%;
    padding: 12px 16px;
    background: var(--color-bg-root); /* #f7f8fa */
    border: 1.5px solid var(--color-border-subtle);
    border-radius: 8px; /* --radius-sm */
    color: var(--color-text-primary); /* #0f172a */
    font-size: 14px;
    font-family: var(--font-sans);
    outline: none;
    transition: all 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.form-input:hover {
    border-color: var(--color-border-strong);
}

.form-input:focus {
    border-color: var(--color-accent); /* #4f46e5 */
    box-shadow: 0 0 0 4px var(--color-accent-glow);
}

.form-input::placeholder {
    color: var(--color-text-muted); /* #94a3b8 */
}
```

### 4.4 Navigation (Sidebar)

```css
.sidebar {
    width: 248px;
    min-height: 100vh;
    background: var(--color-bg-surface);
    border-right: 1px solid var(--color-border-subtle);
    padding: 24px 0;
}

.nav-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 9px 12px;
    border-radius: 8px;
    font-size: 13px;
    font-weight: 500;
    color: var(--color-text-secondary);
    cursor: pointer;
    transition: all 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.nav-item:hover {
    background: var(--color-bg-hover);
    color: var(--color-text-primary);
}

.nav-item.active {
    background: var(--color-accent-light); /* #eef2ff */
    color: var(--color-accent);
    font-weight: 600;
}
```

### 4.5 Badges / Tags

```css
.badge {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 12px;
    border-radius: 100px; /* 胶囊型 */
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.1px;
}

.badge-role {
    background: var(--color-accent-light); /* #eef2ff */
    color: var(--color-accent);
}

.badge-active {
    background: var(--color-success-light); /* #f0fdf4 */
    color: var(--color-success);
}

.badge-active::before {
    content: '';
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--color-success);
}

.badge-disabled {
    background: var(--color-danger-light); /* #fef2f2 */
    color: var(--color-danger);
}

.badge-disabled::before {
    content: '';
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--color-danger);
}
```

### 4.6 Modals / Dialogs

```css
.modal-overlay {
    position: fixed;
    inset: 0;
    background: rgba(15, 23, 42, 0.5);
    z-index: 200;
    display: flex;
    align-items: center;
    justify-content: center;
    backdrop-filter: blur(2px);
    opacity: 0;
    pointer-events: none;
    transition: opacity 250ms cubic-bezier(0.4, 0, 0.2, 1);
}

.modal-overlay.open {
    opacity: 1;
    pointer-events: auto;
}

.modal {
    width: 520px;
    max-width: 94vw;
    background: var(--color-bg-surface);
    border-radius: 16px;
    padding: 38px;
    max-height: 90vh;
    overflow-y: auto;
    box-shadow: 0 20px 25px rgba(0, 0, 0, 0.06), 0 10px 10px rgba(0, 0, 0, 0.03);
    transform: translateY(12px) scale(0.97);
    transition: transform 250ms cubic-bezier(0.4, 0, 0.2, 1);
}

.modal-overlay.open .modal {
    transform: translateY(0) scale(1);
}
```

### 4.7 Tables

```css
.data-table {
    width: 100%;
    border-collapse: separate;
    border-spacing: 0;
    background: var(--color-bg-surface);
    border: 1px solid var(--color-border-subtle);
    border-radius: 12px; /* --radius-md */
    font-size: 13px;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06), 0 1px 2px rgba(0, 0, 0, 0.04);
    overflow: hidden;
}

.data-table thead {
    background: var(--color-bg-root); /* #f7f8fa */
}

.data-table th {
    text-align: left;
    padding: 14px 20px;
    font-weight: 600;
    font-size: 12px;
    color: var(--color-text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.6px;
    border-bottom: 2px solid var(--color-border-subtle);
}

.data-table td {
    padding: 16px 20px;
    border-bottom: 1px solid var(--color-border-subtle);
    vertical-align: middle;
}

.data-table tbody tr:hover {
    background: var(--color-bg-hover);
}

.data-table tbody tr:last-child td {
    border-bottom: none;
}
```

### 4.8 Drawers (Side Sheet)

从右侧滑出的侧边面板，用于新增/编辑较长表单。宽度 560px，全屏高度，遮罩带轻微模糊。

```css
.drawer-overlay {
    position: fixed;
    inset: 0;
    background: rgba(15, 23, 42, 0.4);
    z-index: 300;
    display: flex;
    align-items: stretch;
    justify-content: flex-end;
    backdrop-filter: blur(2px);
    opacity: 0;
    pointer-events: none;
    transition: opacity 250ms cubic-bezier(0.4, 0, 0.2, 1);
}

.drawer-overlay.open {
    opacity: 1;
    pointer-events: auto;
}

.drawer {
    width: 560px;
    max-width: 100vw;
    background: var(--color-bg-surface);
    border-left: 1px solid var(--color-border-subtle);
    padding: 36px 40px;
    overflow-y: auto;
    box-shadow: 0 20px 25px rgba(0, 0, 0, 0.06), 0 10px 10px rgba(0, 0, 0, 0.03);
    transform: translateX(100%);
    transition: transform 250ms cubic-bezier(0.4, 0, 0.2, 1);
}

.drawer-overlay.open .drawer {
    transform: translateX(0);
}

.drawer-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 28px;
}

.drawer-title {
    font-size: 18px;
    font-weight: 700;
    letter-spacing: -0.3px;
}

.drawer-footer {
    display: flex;
    gap: 10px;
    justify-content: flex-end;
    margin-top: 32px;
    padding-top: 24px;
    border-top: 1px solid var(--color-border-subtle);
}

/* 抽屉内表单字段间距 */
.drawer .form-group {
    margin-bottom: 22px;
}
```

### 4.9 Pagination

表格底部分页器。右对齐，包含总条数、每页条数选择器、页码导航。

```css
.pagination {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 6px;
    padding: 16px 0 0;
    font-size: 13px;
    color: var(--color-text-secondary);
}

.pagination-info {
    margin-right: 12px;
    color: var(--color-text-muted);
    font-size: 12px;
}

.pagination-size {
    /* 复用 .select-input 样式 */
    padding: 5px 28px 5px 10px;
    margin-right: 16px;
    font-size: 12px;
    border: 1.5px solid var(--color-border-subtle);
    border-radius: 8px;
    background: var(--color-bg-surface);
    color: var(--color-text-secondary);
}

.page-btn {
    width: 30px;
    height: 30px;
    border-radius: 8px;
    border: 1.5px solid var(--color-border-subtle);
    background: var(--color-bg-surface);
    color: var(--color-text-secondary);
    font-size: 12px;
    font-weight: 500;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.page-btn:hover {
    border-color: var(--color-border-strong);
    color: var(--color-text-primary);
}

.page-btn.active {
    background: var(--color-accent);
    border-color: var(--color-accent);
    color: #fff;
}

.page-btn:disabled {
    opacity: 0.4;
    cursor: not-allowed;
}
```

### 4.10 Empty State

空状态占位，用于列表无数据时引导用户操作。居中布局，含图标、标题、描述文字和可选按钮。

```css
.empty-state {
    text-align: center;
    padding: 80px 20px;
}

.empty-state-icon {
    font-size: 48px;
    margin-bottom: 16px;
    opacity: 0.25;
}

.empty-state-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--color-text-secondary);
    margin-bottom: 6px;
}

.empty-state-desc {
    font-size: 13px;
    color: var(--color-text-muted);
    margin-bottom: 20px;
}
```

### 4.11 Tree Browser

左侧树形浏览器，用于元数据管理的「数据源 → 库/Schema → 表」三级结构。支持展开/收起箭头，激活态蓝色高亮。

```css
.tree-panel {
    width: 240px;
    overflow-y: auto;
    padding: 16px 0;
    background: var(--color-bg-surface);
    border: 1px solid var(--color-border-subtle);
    border-radius: 12px 0 0 12px;
}

.tree-item {
    padding: 7px 16px 7px 12px;
    font-size: 13px;
    color: var(--color-text-secondary);
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 6px;
    transition: all 150ms cubic-bezier(0.4, 0, 0.2, 1);
    user-select: none;
}

.tree-item:hover {
    background: var(--color-bg-hover);
}

.tree-item.active {
    background: var(--color-accent-light);
    color: var(--color-accent);
    font-weight: 600;
}

.tree-arrow {
    font-size: 10px;
    width: 14px;
    text-align: center;
    color: var(--color-text-muted);
    flex-shrink: 0;
    transition: transform 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.tree-arrow.expanded {
    transform: rotate(90deg);
}

.tree-item.lv1 {
    padding-left: 12px;
}

.tree-item.lv2 {
    padding-left: 30px;
}

.tree-item.lv3 {
    padding-left: 48px;
}

.tree-count {
    font-size: 11px;
    color: var(--color-text-muted);
    margin-left: auto;
    background: var(--color-bg-hover);
    padding: 1px 6px;
    border-radius: 100px;
}
```

### 4.12 Metadata Detail Panel

树形浏览器右侧的详情面板，用于展示表列表和字段详情。支持内联编辑表/字段注释。

```css
.detail-panel {
    flex: 1;
    overflow-y: auto;
    padding: 20px 28px;
    background: var(--color-bg-surface);
    border: 1px solid var(--color-border-subtle);
    border-left: none;
    border-radius: 0 12px 12px 0;
}

.detail-breadcrumb {
    font-size: 13px;
    color: var(--color-text-muted);
    margin-bottom: 20px;
}

.detail-breadcrumb a {
    color: var(--color-accent);
    cursor: pointer;
}

.detail-breadcrumb a:hover {
    text-decoration: underline;
}

.detail-title {
    font-size: 18px;
    font-weight: 700;
    margin-bottom: 20px;
    letter-spacing: -0.3px;
}

.metadata-table {
    width: 100%;
    border-collapse: separate;
    border-spacing: 0;
    font-size: 13px;
    border: 1px solid var(--color-border-subtle);
    border-radius: 12px;
    overflow: hidden;
}

.metadata-table th,
.metadata-table td {
    padding: 12px 16px;
    text-align: left;
    border-bottom: 1px solid var(--color-border-subtle);
}

.metadata-table th {
    background: var(--color-bg-root);
    font-size: 12px;
    font-weight: 600;
    color: var(--color-text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.6px;
}

.metadata-table tr:last-child td {
    border-bottom: none;
}

.metadata-comment {
    background: transparent;
    border: 1.5px solid transparent;
    border-radius: 6px;
    padding: 5px 8px;
    font-size: 13px;
    color: var(--color-text-secondary);
    width: 100%;
    transition: all 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.metadata-comment:hover {
    border-color: var(--color-border-subtle);
    background: var(--color-bg-root);
}

.metadata-comment:focus {
    outline: none;
    border-color: var(--color-accent);
    background: var(--color-bg-surface);
    box-shadow: 0 0 0 4px var(--color-accent-glow);
}
```

### 4.13 Progress Bar

采集任务运行中的进度展示。背景浅灰，填充靛蓝色渐变，宽度百分比动态更新。

```css
.progress-bar {
    width: 100%;
    height: 8px;
    background: var(--color-bg-hover);
    border-radius: 100px;
    overflow: hidden;
}

.progress-fill {
    height: 100%;
    background: linear-gradient(90deg, var(--color-accent) 0%, #818cf8 100%);
    border-radius: 100px;
    transition: width 600ms cubic-bezier(0.4, 0, 0.2, 1);
}

.progress-label {
    font-size: 12px;
    color: var(--color-text-muted);
    margin-top: 4px;
}
```

### 4.14 Radio Group

用于采集模式单选（全量 / 增量 / Schema-only）。带图标的卡片式单选，选中态蓝色边框 + 浅蓝背景。

```css
.radio-group {
    display: flex;
    gap: 12px;
}

.radio-card {
    flex: 1;
    padding: 14px 16px;
    border: 1.5px solid var(--color-border-subtle);
    border-radius: 12px;
    cursor: pointer;
    transition: all 150ms cubic-bezier(0.4, 0, 0.2, 1);
    position: relative;
}

.radio-card:hover {
    border-color: var(--color-border-strong);
    background: var(--color-bg-root);
}

.radio-card.selected {
    border-color: var(--color-accent);
    background: var(--color-accent-light);
}

.radio-card input[type='radio'] {
    position: absolute;
    opacity: 0;
}

.radio-card-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text-primary);
    margin-bottom: 4px;
}

.radio-card-desc {
    font-size: 12px;
    color: var(--color-text-muted);
    line-height: 1.45;
}

.radio-card.selected .radio-card-title {
    color: var(--color-accent);
}
```

### 4.15 Multi-select with Chips

用于「数据源 → 库/Schema 多选」。下拉多选后，选中项以 Chip 形式展示在输入框下方，可点击移除。

```css
.chip-list {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 10px;
}

.chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 5px 10px;
    background: var(--color-accent-light);
    color: var(--color-accent);
    border-radius: 100px;
    font-size: 12px;
    font-weight: 600;
}

.chip-remove {
    width: 14px;
    height: 14px;
    border-radius: 50%;
    border: none;
    background: rgba(79, 70, 229, 0.15);
    color: var(--color-accent);
    font-size: 10px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
}

.chip-remove:hover {
    background: var(--color-accent);
    color: #fff;
}
```

### 4.16 Breadcrumb

用于历史记录等需要层级返回的页面。当前项灰色，可点击项靛蓝色。

```css
.breadcrumb {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    color: var(--color-text-muted);
    margin-bottom: 20px;
}

.breadcrumb a {
    color: var(--color-accent);
    cursor: pointer;
}

.breadcrumb a:hover {
    text-decoration: underline;
}

.breadcrumb-separator {
    color: var(--color-border-strong);
}

.breadcrumb-current {
    color: var(--color-text-secondary);
    font-weight: 500;
}
```

### 4.17 Code Log

执行日志展示。等宽字体、深色背景、带时间戳和级别颜色，顶部有复制按钮。

```css
.code-log {
    background: #0f172a;
    border-radius: 12px;
    padding: 16px;
    font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
    font-size: 12px;
    line-height: 1.6;
    color: #e2e8f0;
    max-height: 360px;
    overflow-y: auto;
    position: relative;
}

.code-log-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
    padding-bottom: 12px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.code-log-title {
    font-size: 12px;
    font-weight: 600;
    color: #94a3b8;
    font-family: var(--font-sans);
}

.log-line {
    margin: 2px 0;
    white-space: pre-wrap;
    word-break: break-all;
}

.log-time {
    color: #64748b;
    margin-right: 8px;
}

.log-info {
    color: #60a5fa;
}

.log-success {
    color: #4ade80;
}

.log-error {
    color: #f87171;
}

.log-warn {
    color: #fbbf24;
}
```

### 4.18 Status Variants

Sprint 1 新增状态标签扩展，用于数据源状态（连接中/正常/失败）和任务状态（待运行/运行中/成功/失败）。

```css
.status-dot {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    font-weight: 600;
}

.status-dot::before {
    content: '';
    width: 7px;
    height: 7px;
    border-radius: 50%;
    flex-shrink: 0;
}

.status-normal::before {
    background: var(--color-success);
}

.status-running::before {
    background: var(--color-accent);
    box-shadow: 0 0 0 3px var(--color-accent-glow);
}

.status-failed::before {
    background: var(--color-danger);
}

.status-pending::before {
    background: var(--color-text-muted);
}

.status-connecting::before {
    background: var(--color-warning);
}
```

### 4.19 Field Mapping Table

批量数据同步任务中的字段映射表，三列：源字段 | → | 目标字段，底部可添加行，每行可删除。

```css
.fmt {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-sm);
    overflow: hidden;
}

.fmt th {
    text-align: left;
    padding: 8px 12px;
    background: var(--color-bg-root);
    font-size: 11px;
    font-weight: 700;
    color: var(--color-text-secondary);
    text-transform: uppercase;
    letter-spacing: .5px;
    border-bottom: 1px solid var(--color-border-subtle);
}

.fmt td {
    padding: 8px 12px;
    border-bottom: 1px solid var(--color-border-subtle);
    font-family: var(--font-mono);
}

.fmt tr:last-child td {
    border-bottom: none;
}

.fmt-arrow {
    color: var(--color-accent);
    font-weight: 700;
    text-align: center;
}

.fmt-remove {
    color: var(--color-text-muted);
    cursor: pointer;
    border: none;
    background: none;
    font-size: 16px;
    line-height: 1;
}

.fmt-remove:hover {
    color: var(--color-danger);
}
```

### 4.20 Sync Task Drawer (640px)

比通用 Drawer 更宽（640px），容纳字段映射表和 Cron 预设。底部三按钮（立即执行 | 取消 | 保存）。

```css
.drawer-sync {
    width: 640px;
    max-width: 100vw;
    /* 其余复用 .drawer 样式 */
}

.drawer-footer-3btn {
    display: flex;
    gap: 10px;
    justify-content: flex-end;
    margin-top: 32px;
    padding-top: 24px;
    border-top: 1px solid var(--color-border-subtle);
}
```

### 4.21 Cron Presets

12 个常用 Cron 表达式预设，胶囊型按钮，点击即选。下方预览区展示表达式原文 + 中文语义 + 未来 5 次执行时间。

```css
.cron-presets {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 12px;
}

.cron-preset {
    padding: 5px 10px;
    border: 1.5px solid var(--color-border-subtle);
    border-radius: var(--radius-full);
    font-size: 11px;
    font-weight: 600;
    color: var(--color-text-secondary);
    cursor: pointer;
    background: var(--color-bg-surface);
    transition: all var(--transition-fast);
}

.cron-preset:hover,
.cron-preset.selected {
    border-color: var(--color-accent);
    color: var(--color-accent);
    background: var(--color-accent-light);
}

.cron-preview {
    background: var(--color-bg-root);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-sm);
    padding: 12px 16px;
    margin-top: 12px;
    font-size: 12px;
}

.cron-preview .cron-expr {
    font-family: var(--font-mono);
    font-size: 13px;
    color: var(--color-accent);
    font-weight: 600;
    margin-bottom: 6px;
}

.cron-preview .cron-next {
    color: var(--color-text-muted);
    font-size: 11px;
    line-height: 1.7;
}
```

### 4.22 Compliance Check Sections

合规检查结果页的分组卡片。每组有标题行（含计数），内部有子标题和结果表格。

```css
.cat-section {
    margin-bottom: 18px;
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-sm);
    overflow: hidden;
}

.cat-title {
    font-size: 12px;
    font-weight: 700;
    color: var(--color-text-secondary);
    padding: 10px 16px;
    background: var(--color-bg-root);
    border-bottom: 1px solid var(--color-border-subtle);
    text-transform: uppercase;
    letter-spacing: .5px;
}

.cat-title .count {
    font-weight: 800;
    color: var(--color-text-primary);
}

.cr-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12px;
}

.cr-table th {
    text-align: left;
    padding: 8px 12px;
    background: var(--color-bg-root);
    font-weight: 600;
    color: var(--color-text-secondary);
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: .4px;
    border-bottom: 1px solid var(--color-border-subtle);
}

.cr-table td {
    padding: 8px 12px;
    border-bottom: 1px solid var(--color-border-subtle);
    vertical-align: top;
}

.cr-table tr:last-child td {
    border-bottom: none;
}
```

### 4.23 Data Preview Drawer

从数据源列表「预览」按钮进入。左侧库/Schema 树（200px），右侧表格展示前 100 行数据。

```css
.pv-layout {
    display: flex;
    height: 100%;
}

.pv-tree {
    width: 200px;
    overflow-y: auto;
    border-right: 1px solid var(--color-border-subtle);
    padding: 12px 0;
    flex-shrink: 0;
}

.pv-tree-item {
    padding: 7px 16px;
    font-size: 13px;
    color: var(--color-text-secondary);
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 6px;
    transition: all var(--transition-fast);
    user-select: none;
}

.pv-tree-item:hover {
    background: var(--color-bg-hover);
}

.pv-tree-item.active {
    background: var(--color-accent-light);
    color: var(--color-accent);
    font-weight: 600;
}

.pv-tree-item.lv1 {
    padding-left: 12px;
}

.pv-tree-item.lv2 {
    padding-left: 28px;
    font-size: 12px;
}

.pv-content {
    flex: 1;
    padding: 20px 24px;
    overflow-y: auto;
}

.pv-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12px;
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-sm);
    overflow: hidden;
}

.pv-table th {
    text-align: left;
    padding: 8px 12px;
    background: var(--color-bg-root);
    font-weight: 600;
    color: var(--color-text-secondary);
    font-size: 11px;
    border-bottom: 1px solid var(--color-border-subtle);
    white-space: nowrap;
}

.pv-table td {
    padding: 8px 12px;
    border-bottom: 1px solid var(--color-border-subtle);
    white-space: nowrap;
}

.pv-table tr:last-child td {
    border-bottom: none;
}
```

### 4.24 DAG Canvas (Sprint 3 新增)

DAG 画布为全屏工作区，顶部工具栏 + 左侧节点面板 + 中间画布 + 右侧属性面板。

```css
.canvas-workspace {
    position: fixed;
    inset: 0;
    z-index: 150;
    display: flex;
    flex-direction: column;
    background: var(--color-bg-root);
}

.canvas-toolbar {
    height: 56px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 0 20px;
    background: var(--color-bg-surface);
    border-bottom: 1px solid var(--color-border-subtle);
    box-shadow: var(--shadow-xs);
}

.canvas-body {
    flex: 1;
    display: flex;
    overflow: hidden;
}

.node-palette {
    width: 180px;
    flex-shrink: 0;
    background: var(--color-bg-surface);
    border-right: 1px solid var(--color-border-subtle);
    padding: 20px 14px;
    display: flex;
    flex-direction: column;
    gap: 14px;
}

.palette-node {
    border: 1.5px solid var(--color-border-subtle);
    border-radius: 12px;
    padding: 16px 12px;
    text-align: center;
    cursor: grab;
    background: var(--color-bg-surface);
    transition: all var(--transition-fast);
}

.palette-node:hover {
    border-color: var(--color-accent);
    background: var(--color-accent-light);
    color: var(--color-accent);
}

.canvas-area {
    flex: 1;
    position: relative;
    overflow: hidden;
    background:
        radial-gradient(circle, var(--color-border-subtle) 1px, transparent 1px);
    background-size: 20px 20px;
}

.canvas-area svg {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
}

.dag-node {
    position: absolute;
    width: 180px;
    background: var(--color-bg-surface);
    border: 2px solid var(--color-border-subtle);
    border-radius: 12px;
    box-shadow: var(--shadow-sm);
    cursor: pointer;
    user-select: none;
    transition: box-shadow var(--transition-fast), border-color var(--transition-fast);
}

.dag-node:hover {
    box-shadow: var(--shadow-md);
}

.dag-node.selected {
    border-color: var(--color-accent);
    box-shadow: 0 0 0 4px var(--color-accent-glow);
}

.dag-node.status-success {
    border-color: var(--color-success);
}

.dag-node.status-running {
    border-color: var(--color-accent);
}

.dag-node.status-failed {
    border-color: var(--color-danger);
}

.dag-node.status-skipped {
    border-color: var(--color-warning);
}

.dag-node-header {
    padding: 10px 12px;
    font-size: 13px;
    font-weight: 600;
    border-bottom: 1px solid var(--color-border-subtle);
    display: flex;
    align-items: center;
    gap: 6px;
}

.dag-node-body {
    padding: 10px 12px;
    font-size: 12px;
    color: var(--color-text-secondary);
    line-height: 1.5;
}

.dag-node-port {
    position: absolute;
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: var(--color-text-muted);
    border: 2px solid var(--color-bg-surface);
}

.dag-node-port.input {
    left: -6px;
    top: 50%;
    transform: translateY(-50%);
}

.dag-node-port.output {
    right: -6px;
    top: 50%;
    transform: translateY(-50%);
}

.property-panel {
    width: 260px;
    flex-shrink: 0;
    background: var(--color-bg-surface);
    border-left: 1px solid var(--color-border-subtle);
    padding: 20px;
    overflow-y: auto;
}
```

### 4.25 SQL Task Editor Modal (Sprint 3 新增)

大尺寸弹窗（900px × 600px），内嵌模拟代码编辑器和执行结果区。

```css
.modal-sql {
    width: 900px;
    max-width: 96vw;
    height: 600px;
    max-height: 90vh;
    display: flex;
    flex-direction: column;
    padding: 0;
    overflow: hidden;
}

.sql-modal-header {
    padding: 20px 24px;
    border-bottom: 1px solid var(--color-border-subtle);
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
}

.sql-modal-body {
    flex: 1;
    overflow-y: auto;
    padding: 24px;
}

.sql-editor {
    border: 1.5px solid var(--color-border-subtle);
    border-radius: var(--radius-sm);
    background: #0f172a;
    font-family: var(--font-mono);
    font-size: 13px;
    line-height: 1.7;
    color: #e2e8f0;
    min-height: 220px;
    padding: 14px 16px;
    overflow: auto;
    white-space: pre;
}

.sql-editor .kw { color: #c084fc; }
.sql-editor .fn { color: #60a5fa; }
.sql-editor .str { color: #86efac; }
.sql-editor .num { color: #fbbf24; }
.sql-editor .cm { color: #64748b; }
.sql-editor .line-num {
    display: inline-block;
    width: 28px;
    color: #475569;
    user-select: none;
    text-align: right;
    margin-right: 12px;
}
```

### 4.26 DAG Execution History Panel (Sprint 3 新增)

执行历史弹窗/页面展示每次执行的节点级详情。

```css
.history-node-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin-top: 16px;
}

.history-node-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 14px;
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-sm);
    background: var(--color-bg-root);
    font-size: 13px;
}

.history-node-item.success {
    border-left: 3px solid var(--color-success);
}

.history-node-item.failed {
    border-left: 3px solid var(--color-danger);
}

.history-node-item.skipped {
    border-left: 3px solid var(--color-warning);
}
```

---

## 5. Layout Principles

### 5.1 Spacing System

基于 **4px 基数**，常用间距：

| Token      | 值   | 使用场景                           |
|------------|------|------------------------------------|
| `space-1`  | 4px  | 紧凑内边距、icon-gap               |
| `space-2`  | 8px  | 表单域间距、chip 间距              |
| `space-3`  | 12px | 工具栏元素间距                     |
| `space-4`  | 16px | 表格单元格 padding、卡片内元素间距 |
| `space-5`  | 20px | 表头 padding、表单组间距           |
| `space-6`  | 24px | 页面区块间距、按钮 padding         |
| `space-8`  | 32px | 弹窗内边距                         |
| `space-10` | 40px | 登录卡片 padding                   |
| `space-12` | 48px | 登录卡片上下 padding               |

### 5.2 Grid System

- **侧边栏**：248px 固定宽
- **主内容区**：`flex: 1`，剩余空间自适应
- **内容内边距**：`padding: 28px 36px`
- **不使用 12 列网格**（数据中台以表格和表单为主）

### 5.3 Container

- **登录卡片**：`max-width: 420px`，水平居中
- **弹窗**：`width: 520px; max-width: 94vw`
- **确认对话框**：`max-width: 420px; width: 92%`
- **SQL 编辑器弹窗**：`width: 900px; max-width: 96vw; height: 600px`
- **DAG 画布**：全屏固定定位

### 5.4 留白哲学

- 页面标题与内容之间保留 28px 间距
- 表格上的工具栏用独立背景卡片包裹（12px padding + 边框），形成视觉分组
- 弹窗底部按钮组与表单内容之间用 `border-top` 分割
- 不滥用 margin-bottom，优先用父容器的 padding 控制间距

---

## 6. Depth & Elevation

### 6.1 Shadow System

```css
--shadow-xs: 0 1px 2px rgba(0, 0, 0, 0.04);
/* 用途：紧凑卡片（工具栏、小面板） */

--shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.06), 0 1px 2px rgba(0, 0, 0, 0.04);
/* 用途：表格容器、视图切换 tabs */

--shadow-md: 0 4px 6px rgba(0, 0, 0, 0.04), 0 2px 4px rgba(0, 0, 0, 0.03);
/* 用途：hover 状态的按钮 */

--shadow-lg: 0 10px 15px rgba(0, 0, 0, 0.05), 0 4px 6px rgba(0, 0, 0, 0.03);
/* 用途：未使用（预留给下拉菜单） */

--shadow-xl: 0 20px 25px rgba(0, 0, 0, 0.06), 0 10px 10px rgba(0, 0, 0, 0.03);
/* 用途：登录卡片、弹窗 */
```

### 6.2 Surface Layers

| 层级       | Z-index | 元素                               |
|------------|---------|------------------------------------|
| Background | 0       | `<body>` 底色 `#f7f8fa`            |
| Surface    | 1       | 卡片、表格、侧边栏 `#ffffff`       |
| Elevated   | 100     | 视图切换 tabs（`position: fixed`） |
| Overlay    | 200     | 弹窗遮罩                           |
| Dialog     | 300     | 确认对话框（在弹窗之上）           |
| Canvas     | 150     | DAG 全屏画布                       |

### 6.3 Z-index Scale

```
0    — 默认文档流
100  — 固定定位元素（tabs、top bar）
150  — DAG 画布全屏工作区
200  — 弹窗遮罩
300  — 确认对话框
```

### 6.4 Backdrop Effects

- 弹窗遮罩：`backdrop-filter: blur(2px)` + `rgba(15,23,42,0.5)` 背景
- 其他地方不使用毛玻璃效果

---

## 7. Do's and Don'ts

### Do's

1. **使用 CSS 变量而非硬编码色值**——所有颜色通过 `var(--color-*)` 引用
2. **按钮用 `font-weight: 600`**——在所有尺寸下保持足够的视觉重量
3. **状态标签用 `::before` 伪元素加圆点指示器**——增强可扫描性
4. **表格用 `border-collapse: separate` + `border-radius`**——保持圆角表格
5. **弹窗出现用 `scale(0.97) → scale(1)`**——微小缩放动画增加精致感
6. **所有交互元素 hover 时必须有视觉反馈**——颜色变化或背景变化
7. **使用 Inter 字体的 `letter-spacing: -0.3px ~ -0.5px` 用于大标题**——提升精致感
8. **DAG 节点使用明确边框颜色区分状态**——等待/运行/成功/失败/跳过一目了然

### Don'ts

1. **不要使用 `#000000` 或 `#ffffff` 纯色**——始终使用色板中的中性色
2. **不要给表格使用 `box-shadow` 替代 `border`**——表格需要明确的边界定义
3. **不要在非遮罩场景使用 `backdrop-filter`**——避免毛玻璃滥用
4. **不要让次要操作和主要操作视觉权重相同**——始终区分 Primary / Outline / Ghost
5. **不要使用超过 3 层 shadow 叠加**——保持阴影系统简洁
6. **不要在正文中使用小于 12px 的字号**——11px 仅限 uppercase 导航标签
7. **不要给按钮使用过大的圆角（>12px）**——按钮用 8px，保持专业感
8. **不要让画布网格过于显眼**——使用浅灰色点阵背景，不能喧宾夺主

---

## 8. Responsive Behavior

### 8.1 Breakpoints

| 断点        | 宽度           | 行为                                              |
|-------------|----------------|---------------------------------------------------|
| **Mobile**  | < 768px        | 侧边栏折叠为图标模式（56px），主内容 padding 缩小 |
| **Tablet**  | 768px - 1024px | 侧边栏正常显示，表格水平滚动                      |
| **Desktop** | > 1024px       | 完整布局，侧边栏 248px                            |

### 8.2 Mobile 适配策略

```css
@media (max-width: 768px) {
    .sidebar {
        width: 56px;
        overflow: hidden;
    }

    .sidebar-brand-text,
    .nav-section-title,
    .nav-item span:not(.nav-item-icon) {
        display: none;
    }

    .nav-item {
        justify-content: center;
        padding: 10px;
    }

    .main-content {
        padding: 20px 16px;
    }

    .header-row {
        flex-direction: column;
        gap: 16px;
    }

    .modal {
        width: 100%;
        border-radius: 0;
    }

    .toolbar {
        flex-wrap: wrap;
    }

    .search-input {
        width: 100%;
    }

    .node-palette,
    .property-panel {
        display: none;
    }
}
```

### 8.3 Touch Targets

- 最小触摸目标： **36px × 36px**（导航项、操作按钮、关闭按钮）
- 表格操作按钮：水平排列，间距 ≥ 6px
- DAG 画布节点在 touch 设备上最小 44px 可点击区

### 8.4 Font Scaling

- 不使用 `clamp()` 流体字号
- Mobile 端字号与 Desktop 保持一致（13-14px 已在移动端可读范围内）
- 页面标题在 Mobile 端保持 24px（足够大但不过大）

---

## 9. Agent Prompt Guide

### 9.1 Quick Reference

```
DataNest Sprint 3 — 快速参考卡片

色彩:
  底色: #f7f8fa | 卡片: #ffffff | 强调色: #4f46e5
  文字: #0f172a (主) / #475569 (辅) / #94a3b8 (弱)
  语义: #16a34a (成功) / #dc2626 (危险) / #d97706 (警告)
  DAG 节点: 等待 #94a3b8 / 运行 #4f46e5 / 成功 #16a34a / 失败 #dc2626 / 跳过 #d97706

字体:
  族: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif
  标题: 24px/800/-0.5px  |  正文: 14px/400
  表头: 12px/600/uppercase/0.6px  |  Badge: 12px/600

圆角:
  按钮/输入框: 8px | 表格/工具栏: 12px | 卡片/弹窗: 16px

阴影:
  xs: 0 1px 2px rgba(0,0,0,.04)
  sm: 0 1px 3px rgba(0,0,0,.06), 0 1px 2px rgba(0,0,0,.04)
  xl: 0 20px 25px rgba(0,0,0,.06), 0 10px 10px rgba(0,0,0,.03)

间距: 4px 基数 (4/8/12/16/20/24/32/40/48)
```

### 9.2 Component Prompts

#### Prompt 1: 数据开发项目列表页

```
基于 DataNest DESIGN.md 生成数据开发项目列表页。要求：
- 左侧 248px 侧边栏（"数据开发"菜单项高亮；DAG 执行历史位于"执行历史"分组下）
- 右侧主内容区：页面标题 + "+ 新建项目" 按钮
- 工具栏：搜索框 + 查询/重置按钮
- 数据表格列：项目名称 | 项目描述 | DAG 数 | 操作
- 操作：进入 / 编辑(ghost) / 删除(danger ghost)
- 空状态：📭 图标 + "暂无项目" + 引导文字
- 新建/编辑项目弹窗：项目名称* / 项目描述 / 保存/取消
- 删除确认弹窗：列出该项目下所有 DAG 名称
```

#### Prompt 2: DAG 列表页

```
基于 DataNest DESIGN.md 生成 DAG 列表页。要求：
- 面包屑：数据开发 / {项目名称}
- 返回项目列表按钮
- 表格列：DAG 名称 | 节点数/类型 | 触发方式 | Cron 表达式 | 调度状态 | 状态 | 最近执行 | 操作
- Cron 表达式列：定时任务展示表达式，手动任务展示 "—"，使用等宽字体
- 状态标签：成功(绿点) / 失败(红点) / 运行中(蓝点旋转) / 未执行(灰点) / 已停用
- 操作：编辑 / 执行 / 历史 / 删除
- 新建 DAG 按钮进入全屏画布
```

#### Prompt 3: DAG 画布页

```
基于 DataNest DESIGN.md 生成 DAG 画布页。要求：
- 全屏固定定位，z-index 150
- 顶部工具栏：返回列表 / DAG 名称输入 / 触发方式 / Cron / 调度状态开关 / 保存 / 执行
- 左侧节点面板：SQL 任务节点、同步任务节点（可拖拽）
- 中间画布：点阵背景，可放置节点，SVG 连线，节点有输入/输出端口
- 右侧属性面板：选中节点展示名称/类型/状态/耗时等只读信息
- 节点状态通过边框颜色区分
```

#### Prompt 4: SQL 任务编辑弹窗

```
基于 DataNest DESIGN.md 生成 SQL 任务编辑弹窗。要求：
- 大尺寸弹窗 900px × 600px
- 节点名称* 输入框
- 模拟 Monaco 编辑器区域：深色背景、行号、Doris SQL 语法高亮
- 工具栏按钮：执行 ▶ / 格式 / 全选 / 撤销 / 重做
- 执行结果区：展示每条语句的成功/失败和影响行数
- 底部：运行测试(primary) + 保存(primary)
```

#### Prompt 5: 同步任务节点选择弹窗

```
基于 DataNest DESIGN.md 生成同步任务节点选择弹窗。要求：
- 弹窗宽度 560px
- 节点名称* / 选择同步任务* 下拉
- 选中后下方只读展示任务摘要：源表、目标表、同步模式、状态
- 底部：保存(primary) / 取消(outline)
```

#### Prompt 6: DAG 执行历史页面

```
基于 DataNest DESIGN.md 生成 DAG 执行历史页面。要求：
- 左侧 248px 侧边栏（"执行历史"分组下"DAG 执行历史"菜单项高亮）
- 面包屑：数据开发 / DAG 执行历史
- 工具栏筛选条件：DAG 名称搜索、状态下拉、触发方式下拉、执行时间起止（datetime-local）
- 表格列：执行时间 | 所属 DAG | 执行方式 | 状态 | 耗时 | 节点执行情况 | 操作
- 点击展开显示微缩 DAG 拓扑图（非平铺列表）
- 微缩图复用画布节点位置与依赖连线，节点边框标识状态：成功(绿) / 失败(红) / 跳过(橙) / 运行中(蓝)
- 图例说明状态色，失败记录支持"重跑失败节点"操作
```

### 9.3 Iteration Guide (AI 生成 UI 时的迭代建议)

1. **先建 CSS 变量文件**：在 `:root` 中定义本文档第 2 章所有变量，后续所有组件通过 `var()` 引用
2. **色彩一致性检查**：确保没有任何硬编码色值，所有颜色都来自变量
3. **字体一致性**：全局设置 `font-family: var(--font-sans)` 和 `-webkit-font-smoothing: antialiased`
4. **阴影系统**：表格用 `shadow-sm`，弹窗用 `shadow-xl`，不要混用
5. **圆角克制**：按钮/输入框统一 8px，卡片/弹窗 16px，不要出现 4px 或 20px 圆角
6. **hover 必须有反馈**：所有可点击元素在 hover 时必须有颜色或背景变化
7. **状态覆盖完整**：每个交互组件必须覆盖 default / hover / active / disabled / focus 五个状态
8. **弹窗动画**：使用 `opacity + transform: scale()` 组合，不要用 `display: none/block` 切换
9. **表格数据对齐**：文本列左对齐，操作列右对齐，数字列右对齐
10. **移动端不隐藏功能**：仅折叠侧边栏为图标模式，功能入口不丢失
11. **DAG 节点状态可视化**：等待灰、运行蓝、成功绿、失败红、跳过橙，边框颜色变化

---

## 附录 A：与 PRD 的映射关系

| PRD 交互需求              | DESIGN.md 对应组件                                        | 关键参数                                       |
|---------------------------|-----------------------------------------------------------|------------------------------------------------|
| 数据开发项目列表          | 4.7 Tables + 4.5 Badges + 4.6 Modals                      | table-hover, badge-active/disabled             |
| DAG 列表                  | 4.7 Tables + 4.16 Breadcrumb                              | breadcrumb, status-dot                         |
| DAG 画布                  | 4.24 DAG Canvas                                           | canvas-workspace, dag-node                     |
| SQL 任务编辑器            | 4.25 SQL Task Editor Modal                                | modal-sql, sql-editor                          |
| 同步任务节点选择          | 4.6 Modals + 4.3 Inputs                                   | modal, form-input, select                      |
| 执行历史                  | 4.7 Tables + 4.16 Breadcrumb + 4.26 DAG Execution History | table-hover, datetime-local, history-node-list |
| 未保存拦截                | 4.6 Modals                                                | modal-overlay, modal                           |
| 多表同步（Sprint 2 增强） | 4.15 Multi-select + 4.19 Field Mapping                    | chip-list, fmt                                 |
| 速率限流（Sprint 2 增强） | 4.3 Inputs + checkbox                                     | form-input, checkbox                           |

## 附录 B：文件结构建议

```
src/
├── styles/
│   └── tokens.css              # 第2章所有 CSS 变量
├── components/
│   ├── Button/
│   ├── Input/
│   ├── Badge/
│   ├── Modal/
│   ├── Table/
│   ├── Sidebar/
│   ├── ErrorCard/
│   ├── DagCanvas/
│   ├── DagNode/
│   ├── SqlEditor/
│   └── SyncTaskSelector/
└── pages/
    ├── DataDev/
    │   ├── ProjectList.tsx
    │   ├── DagList.tsx
    │   ├── DagCanvas.tsx
    │   └── ExecutionHistory.tsx
    └── BatchSync/
        └── SyncTaskDrawer.tsx
```
