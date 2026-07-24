# DataNest DESIGN.md — Sprint 0 用户与权限管理

> **设计方向**：现代企业亮色风格（参考 Stripe + Vercel 精品质感）
> **AI 消费**：本文档供 Cursor / Claude Code / Copilot 等 AI 编程代理直接解析
> **版本**：v1.0 | **日期**：2026-07-23

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

---

## 3. Typography Rules

### 3.1 Font Family

```css
--font-sans:

'Inter'
,
-apple-system, BlinkMacSystemFont,

'Segoe UI'
,
sans-serif

;
```

- **Inter** 从 Google Fonts 加载（weights: 400, 500, 600, 700, 800）
- 回退栈：系统原生字体，确保无网络时的可用性
- 不使用等宽字体作为 UI 字体（代码编辑器场景另配）

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

/* ══ Ghost Button (表格操作) ══ */
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

### 5.4 留白哲学

- 页面标题与内容之间保留 28px 间距
- 表格上的工具栏用独立背景卡片包裹（12px padding + 边框），形成视觉分组
- 弹窗底部按钮组与表单内容之间用 `border-top` 分割
- 不滥用 margin-bottom，优先用父容器的 padding 控制间距

---

## 6. Depth & Elevation

### 6.1 Shadow System

```css
--shadow-xs:

0
1
px

2
px

rgba
(
0
,
0
,
0
,
0.04
)
;
/* 用途：紧凑卡片（工具栏、小面板） */

--shadow-sm:

0
1
px

3
px

rgba
(
0
,
0
,
0
,
0.06
)
,
0
1
px

2
px

rgba
(
0
,
0
,
0
,
0.04
)
;
/* 用途：表格容器、视图切换 tabs */

--shadow-md:

0
4
px

6
px

rgba
(
0
,
0
,
0
,
0.04
)
,
0
2
px

4
px

rgba
(
0
,
0
,
0
,
0.03
)
;
/* 用途：hover 状态的按钮 */

--shadow-lg:

0
10
px

15
px

rgba
(
0
,
0
,
0
,
0.05
)
,
0
4
px

6
px

rgba
(
0
,
0
,
0
,
0.03
)
;
/* 用途：未使用（预留给下拉菜单） */

--shadow-xl:

0
20
px

25
px

rgba
(
0
,
0
,
0
,
0.06
)
,
0
10
px

10
px

rgba
(
0
,
0
,
0
,
0.03
)
;
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

### 6.3 Z-index Scale

```
0    — 默认文档流
100  — 固定定位元素（tabs、top bar）
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

### Don'ts

1. **不要使用 `#000000` 或 `#ffffff` 纯色**——始终使用色板中的中性色
2. **不要给表格使用 `box-shadow` 替代 `border`**——表格需要明确的边界定义
3. **不要在非遮罩场景使用 `backdrop-filter`**——避免毛玻璃滥用
4. **不要让次要操作和主要操作视觉权重相同**——始终区分 Primary / Outline / Ghost
5. **不要使用超过 3 层 shadow 叠加**——保持阴影系统简洁
6. **不要在正文中使用小于 12px 的字号**——11px 仅限 uppercase 导航标签
7. **不要给按钮使用过大的圆角（>12px）**——按钮用 8px，保持专业感

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
}
```

### 8.3 Touch Targets

- 最小触摸目标： **36px × 36px**（导航项、操作按钮、关闭按钮）
- 表格操作按钮：水平排列，间距 ≥ 6px

### 8.4 Font Scaling

- 不使用 `clamp()` 流体字号
- Mobile 端字号与 Desktop 保持一致（13-14px 已在移动端可读范围内）
- 页面标题在 Mobile 端保持 24px（足够大但不过大）

---

## 9. Agent Prompt Guide

### 9.1 Quick Reference

```
DataNest Sprint 0 — 快速参考卡片

色彩:
  底色: #f7f8fa | 卡片: #ffffff | 强调色: #4f46e5
  文字: #0f172a (主) / #475569 (辅) / #94a3b8 (弱)
  语义: #16a34a (成功) / #dc2626 (危险) / #d97706 (警告)

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

以下 Prompt 可直接复制给 AI 编程代理生成对应组件：

#### Prompt 1: 登录页面

```
基于 DataNest DESIGN.md 生成登录页面组件。要求：
- 居中卡片布局，max-width 420px
- 顶部品牌 Logo + "DataNest" 文字
- 用户名输入框（带 label）
- 密码输入框（带显示/隐藏切换图标）
- "记住登录状态（7天）" 复选框
- 登录按钮（空值时 disabled，opacity 0.45）
- 底部"没有账号？联系管理员创建"文字
- 错误提示：红色卡片 + 警告图标，5秒自动消失
- 三种错误场景：用户不存在 / 密码错误 / 账号已禁用
```

#### Prompt 2: 用户管理列表页

```
基于 DataNest DESIGN.md 生成用户管理列表页面。要求：
- 左侧 248px 侧边栏（"系统管理" 分组含"用户管理"和"角色管理"）
- 右侧主内容区：页面标题 + "创建用户"按钮
- 工具栏：搜索框 + 角色下拉筛选 + 状态下拉筛选 + 用户计数
- 数据表格列：用户名(#编号) | 角色(badge) | 邮箱 | 状态(圆点+badge) | 创建时间 | 操作
- 正常用户操作：详情 / 编辑(ghost) / 禁用(danger ghost)
- 已禁用用户操作：详情 / 编辑(ghost) / 启用(success ghost)
- 表格 hover 行高亮，表头 uppercase
```

#### Prompt 3: 创建/编辑用户弹窗

```
基于 DataNest DESIGN.md 生成创建/编辑用户弹窗。要求：
- 居中弹窗，520px 宽，backdrop-filter blur 遮罩
- 出现动画：scale(0.97)→scale(1)，250ms ease
- 表单字段：用户名* / 密码* / 确认密码* / 角色*(chip多选) / 邮箱 / 手机号
- 编辑模式下密码字段显示"不修改则留空"
- 角色 chip 多选：选中态蓝底蓝字，未选中灰底灰字
- 底部：取消(outline) + 创建/保存(primary) 按钮
```

#### Prompt 4: 确认对话框

```
基于 DataNest DESIGN.md 生成确认对话框。要求：
- 小尺寸居中对话框，max-width 420px
- 标题"确认操作" + 描述文字
- 使用场景："确认禁用用户 xxx？禁用后该用户将无法登录。"
- 底部：取消(outline) + 确认禁用(红色 primary) 按钮
- 启用场景按钮改为绿色
```

#### Prompt 5: 错误提示卡片

```
基于 DataNest DESIGN.md 生成错误提示组件。要求：
- 红色浅底卡片（#fef2f2），红色边框（#fecaca）
- 左侧警告图标 + 错误文案
- 出现在表单上方
- 支持关闭按钮或 5 秒自动消失
- 三种文案：
  "该用户不存在，请检查用户名或联系管理员创建账号"
  "密码错误，请重试"
  "该账号已被禁用，请联系管理员"
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

---

## 附录 A：与 PRD 的映射关系

| PRD 交互需求         | DESIGN.md 对应组件                   | 关键参数                           |
|----------------------|--------------------------------------|------------------------------------|
| 登录页面（居中卡片） | 4.2 Cards + 4.3 Inputs + 4.1 Buttons | card-xl, input-focus-ring          |
| 登录失败提示         | 9.2 Prompt 5（错误卡片）             | danger-light 背景                  |
| 用户列表             | 4.7 Tables + 4.5 Badges              | table-hover, badge-active/disabled |
| 创建用户弹窗         | 4.6 Modals + role chip               | modal-animation, accent-light chip |
| 编辑用户（密码留空） | 4.6 Modals + placeholder logic       | muted hint text                    |
| 禁用/启用确认        | 4.6 Dialogs（cbox）                  | danger-green button color          |
| 左侧导航             | 4.4 Navigation                       | 248px sidebar, active accent       |
| 搜索 + 筛选          | 4.3 Inputs + select                  | toolbar-card wrapper               |

## 附录 B：文件结构建议

```
src/
├── styles/
│   └── tokens.css          # 第2章所有 CSS 变量
├── components/
│   ├── Button/
│   ├── Input/
│   ├── Badge/
│   ├── Modal/
│   ├── Table/
│   ├── Sidebar/
│   └── ErrorCard/
└── pages/
    ├── Login/
    └── UserManagement/
```
