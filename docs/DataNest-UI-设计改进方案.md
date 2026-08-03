# DataNest UI 设计改进方案

> **版本**：v1.2 | **日期**：2026-08-03
> **继承说明**：本方案继承 Sprint 0 ~3 `docs/sprint*/ui/DESIGN.md` 的全部设计 token，不改动既有 token 体系，只做一致性收敛与身份强化。
> **范围**：Phase 1~7 全量实施。 **首页（`pages/home`）不在此范围内，不修改不回归。**

---

## 0. 背景与动机

现有界面 token 体系、组件收敛（`DsButton`/`DsModal`/`DsStatusBadge`/原型表格覆盖）执行扎实，但存在三类问题：

1. **身份缺失**：整体是"通用后台皮肤"，看不出来它管理的是数据的流动；favicon 里现成的嵌套六边形品牌资产没有落地到 UI。
2. **一致性漏洞**：26 处 emoji 与全站 `HiOutline*` 图标体系混用；首页以外仍有零散裸色/裸 hex。
3. **文档与实现脱节**：DESIGN.md §8 写了响应式折叠但全站 `@media` 零命中；面包屑与页面 h1 双重标题。

本方案在 **不重做组件结构、不引入字体资产**的前提下，用六个 Phase 收敛问题并建立数据平台的字符级身份。

## 0.1 全局扫描结论（Phase 6 依据）

对前端工程全量扫描（`rg` + 人工核对）得到的关键证据，构成 Phase 6 追加项的决策依据：

| 主题           | 证据                                                                                              | 处理                              |
|----------------|---------------------------------------------------------------------------------------------------|-----------------------------------|
| antd 原生组件  | Select ~20 文件、Tooltip 11、Spin 11、Modal 7、Switch 3、Tabs 2                                   | Phase 6-A ConfigProvider 深度对齐 |
| focus 写法     | `focus:` 与 `focus-visible:` 双写法并存（各 20+ 处）；DsButton/DsIconButton/Sidebar 无 focus 样式 | Phase 6-B 全局兜底                |
| muted 对比度   | `text-ds-text-muted` 全站 222 处，#94a3b8 白底对比度 ~2.9:1                                       | Phase 6-C 全站提亮 → #64748b      |
| 数字千分位     | `toLocaleString` 全站 0 处                                                                        | Phase 6-D formatNumber            |
| ErrorBoundary  | 全站 0 处                                                                                         | Phase 6-E 新增                    |
| 暗色           | `prefers-color-scheme` 全站 0 处                                                                  | 声明 light-only（Phase 6-G 远期） |
| DAG 节点类型色 | `Editor.tsx:137-144/1680-1705` 两组裸 hex 画布+面板重复                                           | Phase 6-H token 化                |
| history 页裸色 | `collect-tasks/history-global/index.tsx:47,54` 有 `bg-blue-50` / `bg-slate-100`                   | Phase 6-I 清理                    |
| 原生 `<Modal>` | `dags/index.tsx:343,395`、`dags/project.tsx:502`                                                  | Phase 6-J 收敛                    |

---

## 1. 设计决策

### 1.1 字体策略（纯系统字体栈）

当前 `index.html` 未加载任何 webfont，`tailwind.config.js` 的 `sans: ['Inter', ...]` 实际从未生效，全站跑系统字体。方案：

- **不引入 webfont**（内网/离线部署友好，零资产）。
- `sans` 调整为中文感知系统栈，明确 CJK 回退顺序。
- 新增正式 `mono` 栈，供数字/标识符/代码使用。

### 1.2 图标策略

全站统一 `react-icons/hi2` 的 `HiOutline*` 轮廓系列。已逐一核实图标在 `react-icons/hi2` 中存在（见 Phase 1 映射表）。

### 1.3 品牌策略

复用 `public/favicon.svg` 的嵌套六边形（数据"蜂巢/网络"母题，indigo 渐变），抽为 `LogoMark` 组件落地侧边栏与登录页，替代「DN
文字块」。

### 1.4 数字身份

数据平台的本质是数字：Snowflake ID、cron、端口、行数、耗时、百分比。数据列统一 `font-mono + tabular-nums`，与既有 `font-mono`（41
处，用于表名/SQL/日志）惯例合并，形成"数据即数字"的字符级签名。

---

## 2. Phase 1 — 图标与品牌一致性

### 2.1 侧边栏菜单图标（`src/components/Sidebar.tsx`）

`allMenus` 的 `icon` 字段由 emoji 字符串改为 `JSX.Element`（模块级静态 JSX），渲染方式不变。

| 菜单             | 原 emoji | 新图标                            |
|------------------|----------|-----------------------------------|
| 首页             | 🏠       | `HiOutlineHome`                   |
| 数据源管理       | 📦       | `HiOutlineServer`                 |
| 批量数据同步任务 | 🔄       | `HiOutlineArrowsRightLeft`        |
| 项目管理         | 🔧       | `HiOutlineFolderOpen`             |
| 元数据采集任务   | ⏱        | `HiOutlineClock`                  |
| 元数据管理       | 📋       | `HiOutlineTableCells`             |
| 数据标准         | 📏       | `HiOutlineScale`                  |
| 同步执行历史     | 🔄       | `HiOutlineClipboardDocumentList`  |
| 采集执行历史     | ⏱        | `HiOutlineClipboardDocumentCheck` |
| DAG 执行历史     | 🔧       | `HiOutlineQueueList`              |
| 用户管理         | 👥       | `HiOutlineUsers`                  |
| 告警中心         | 📢       | `HiOutlineBellAlert`              |

### 2.2 DAG 节点类型图标（`src/pages/engineering/dags/Editor.tsx`）

`NODE_TYPE_ICON` 由 `Record<NodeType, string>` 改为 `Record<NodeType, JSX.Element>`，画布节点与左侧面板共用：

| 类型      | 原 emoji | 新图标                       |
|-----------|----------|------------------------------|
| SQL       | 📝       | `HiOutlineCodeBracket`       |
| SYNC      | 🔄       | `HiOutlineArrowsRightLeft`   |
| PYTHON    | 🐍       | `HiOutlineCodeBracketSquare` |
| CONDITION | 🔀       | `HiOutlineVariable`          |
| SUB_DAG   | 📦       | `HiOutlineRectangleStack`    |

### 2.3 搜索框图标（`src/components/SearchInput.tsx`）

移除 `🔍 ${placeholder}` 前缀拼接，改为输入框内左侧绝对定位 `HiOutlineMagnifyingGlass`（muted 色），input 加 `pl-9`。

### 2.4 血缘提示（`src/pages/governance/metadata/lineage/LineageGraphPage.tsx`）

`💡 xxx` → `HiOutlineLightBulb` 内联图标 + 文案。

### 2.5 编辑器结果标记（`SqlEditorModal.tsx`、`PythonEditorModal.tsx`）

`✅`/`❌` → `HiOutlineCheckCircle`（`text-ds-success`）/ `HiOutlineXCircle`（`text-ds-danger`）。

### 2.6 品牌 Logo 组件（新建 `src/components/LogoMark.tsx`）

将 `public/favicon.svg` 的嵌套六边形 SVG 抽为组件（`size?: number` prop），替换：

- `Sidebar.tsx`「DN」方块 → `LogoMark size={28}`
- `src/pages/login/index.tsx`「DN」方块 → `LogoMark size={40}`

---

## 3. Phase 2 — 字体栈与数据数字身份

### 3.1 `tailwind.config.js` fontFamily

```js
fontFamily: {
    sans: ['-apple-system', 'BlinkMacSystemFont', "'Segoe UI'", "'PingFang SC'", "'Hiragino Sans GB'", "'Microsoft YaHei'", "'Helvetica Neue'", 'Arial', 'sans-serif'],
        mono
:
    ["'SFMono-Regular'", 'ui-monospace', 'Menlo', 'Consolas', "'Liberation Mono'", 'monospace'],
}
```

> `mono` 栈同时供既有 `font-mono` 用法与新增数据列使用；不新增任何字体文件。

### 3.2 `src/styles/tokens.css`

- 确认/兜底 `tabular-nums` utility（`font-variant-numeric: tabular-nums`），供数据列对齐。
- 侧边栏响应式折叠所需 class（见 Phase 4）。

### 3.3 数据列应用 `font-mono tabular-nums`

涉及列表页的数字/标识符列：

| 文件                                                 | 列                                 |
|------------------------------------------------------|------------------------------------|
| `src/pages/engineering/sync-jobs/index.tsx`          | 耗时、源/目标行数                  |
| `src/pages/engineering/sync-jobs/history-common.tsx` | 源/目标表名（已 mono）、行数、耗时 |
| `src/pages/governance/collect-tasks/index.tsx`       | 耗时、状态计数                     |
| `src/pages/engineering/datasources/index.tsx`        | `host:port/db`、最近连接时间       |
| `src/pages/engineering/dag-executions/index.tsx`     | 耗时、ID、节点计数                 |
| `src/pages/governance/metadata/index.tsx`            | 字段数等计数                       |

---

## 4. Phase 3 — 标题层级收敛

### 4.1 `src/utils/breadcrumb.ts`

- `BreadcrumbEntry` 增加 `leaf?: boolean`。
- `resolveBreadcrumb` 精确匹配到 `leaf` 页时返回 `[]`；深层路由前缀匹配逻辑不变。

### 4.2 标记为 leaf 的页面（精确匹配时不显示面包屑）

`/system/users`、`/system/alert-center`、`/engineering/datasources`、`/engineering/sync-jobs`、
`/engineering/sync-job-history`、`/engineering/dag-executions`、`/engineering/dags`、`/governance/collect-tasks`、
`/governance/collect-task-history`、`/governance/metadata`、`/governance/data-standards`。

**不标记**：`/`（本就返回空）、`/governance/metadata/lineage` 等深层/动态路由。

> `document.title`（`resolveMenuTitle`）与路由不受影响。

### 4.3 `src/components/Breadcrumb.tsx`

无需改动（`resolveBreadcrumb` 返回空即不渲染）。

---

## 5. Phase 4 — 响应式与布局容器

### 5.1 侧边栏折叠（对齐 DESIGN.md §8.1/§8.2）

- `Sidebar.tsx`：为品牌文字、分组标题、菜单文字补稳定 class（`sb-brand-text`、`sb-group`、`sb-label`），图标 `sb-icon`。
- `src/styles/tokens.css`：

```css
@media (max-width: 1023px) {
    .sb-brand-text, .sb-group, .sb-label {
        display: none;
    }

    /* 侧边栏由 248px 收窄为 56px，图标居中 */
}
```

- `src/components/Layout.tsx`：`ml-[248px]` → `lg:ml-[248px]`，<lg 时 `ml-14`。

### 5.2 内容区宽度（最终决策：保持通栏）

~~按页 opt-in `mx-auto w-full max-w-[1440px]`~~ **已回滚**
。实施后发现仅部分页面加了限宽导致宽屏下"有的贴边、有的留白"不一致（用户反馈：告警中心有宽间隙、用户管理正常）。最终统一为
**通栏**（与用户管理一致），列表页根节点不加任何 max-width。

### 5.4 列表页分页器与底部间距（最终决策）

- **分页器跟随表格**：所有列表页为自然流，分页器紧贴表格最后一行下方（`gapTableToPag = 0`），卡片高度=内容高度，不撑满、不钉底。
- **曾尝试"卡片撑满+表格体内滚+分页器贴底"（`useTableScrollY` + `.ds-table-fill` flex CSS）**
  ：能消除行数少时的底部空隙，但用户反馈"分页器被固定、不美观"， **已整体回退**（删除 hook、CSS，10 个页面还原）。
- **移除表格卡片尾部 `mb-ds-8`**：卡片底部外边距在内容接近满屏时会把滚动区撑出（1536×864 下告警规则页出现 30px
  轻微竖向滚动）。间距改由 `main` 的 `p-ds-6` 容器内边距承担，对齐 DESIGN.md"用 padding 不用子元素 margin"。
- **操作列固定**：告警规则/告警历史/同步历史/采集历史/DAG 执行历史 5 处补 `fixed: 'right'`。

### 5.3 工具栏

`DsToolbar.tsx` 已有 `flex-wrap`，无需改动；回归确认 `extra` 按钮组窄屏不溢出。

---

## 6. Phase 5（签名元素）— 流水线脊线

### 6.1 `StatusSpine` 组件（新建 `src/components/StatusSpine.tsx`）

首列固定细脊线（约 6px），颜色复用 `--color-node-*`（SUCCESS/FAILED/RUNNING/WAITING/SKIPPED），作为跨页统一的"管道状态"语言。

### 6.2 应用范围

数据源、同步任务、采集任务、DAG、执行历史各列表行首。状态 → 颜色映射统一走现有 `NODE_STATUS_COLOR`（
`src/constants/statusColors.ts`）。

### 6.3 源 → 目标连接符

同步/采集历史行的「源 → 目标」渲染（`history-common.tsx` 已有）统一样式，数字/标识符用 mono。

---

## 7. Phase 6（全局扫描追加）— antd 深度对齐 · 可访问性 · 工程兜底

> 来源：对前端工程全量扫描后的追加决策（证据见 §0 扫描结论）。首页不涉及。

### 7.1 A — antd ConfigProvider 深度对齐（`src/main.tsx`）

现状：仅设置 `colorPrimary / colorLink / borderRadius`，而全站大量使用 antd 原生组件（Select ~20 文件、Tooltip 11、Spin
11、Modal 7、Switch 3、Tabs 2、Progress/Popover/Tag），密度与文字仍为 antd 默认。`Modal.confirm` 已走 `prototype-modal`
包装，无需处理。

在 `ConfigProvider.theme.token` 中扩充：

```js
fontSize: 13,
    controlHeight
:
32,
    fontFamily
:
<中文感知 sans 栈，与
tailwind
一致 >,
    colorText
:
rgb(15
23
42
),
colorTextSecondary: rgb(71
85
105
),
colorTextTertiary: rgb(100
116
139
),   // 与提亮后 muted 同值
colorBorder: rgb(205
211
220
),
colorBorderSecondary: rgb(226
230
237
),
colorBgContainer: #ffffff,
    colorBgElevated
:
#ffffff,
```

对齐 ds token，让 Select 下拉 / Switch / Tabs / Tooltip 等融入系统。一处改动，低风险。

### 7.2 B — focus-visible 统一（全局兜底）

现状：`focus:` 与 `focus-visible:` 双写法并存（各 20+ 处），`DsButton` / `DsIconButton` / Sidebar 无键盘焦点样式。

在 `src/styles/tokens.css` 增加全局兜底（最小改动，不逐文件迁移）：

```css
:focus-visible {
    outline: 2px solid rgb(var(--color-accent));
    outline-offset: 2px;
}
```

### 7.3 C — muted 对比度提亮（已决策：全站提亮）

现状：`--color-text-muted: #94a3b8` 白底对比度约 2.9:1，低于 WCAG AA；全站 222 处使用。

决策： **全站提亮** `--color-text-muted` 从 `#94a3b8` → `#64748b`（对比度约 4.6:1），占位符/辅助文字整体变深一档，接受一次全站视觉微调。同步
`main.tsx` 的 `colorTextTertiary` 与 `tailwind.config.js` 引用，无需改业务代码。

### 7.4 D — 数字千分位（`src/utils/format.ts`）

现状：`toLocaleString` 全站 0 处，行数/字段数/资产数等大数字裸显示。

新增 `formatNumber(value): string`（`toLocaleString('zh-CN')`），与 Phase 2 的 `tabular-nums` 配合，应用于行数/计数类数据列。

### 7.5 E — 全局 ErrorBoundary（新建 `src/components/ErrorBoundary.tsx`）

现状：0 处，渲染期异常会白屏。

新建类组件 `ErrorBoundary`（fallback：错误提示 + 刷新按钮），包裹在 `Layout` 内容区外层（或路由层）。

### 7.6 F — 登录页品牌时刻（可选，Phase 1 收尾）

复用 `LogoMark`，把嵌套六边形/点阵母题做进登录页背景，让品牌资产落地不孤立。

### 7.7 H — DAG 节点类型色 token 化（`src/pages/engineering/dags/Editor.tsx`）

现状：`Editor.tsx:137-144/1680-1705` 两组裸 hex 画布+面板重复（violet 条件分支、teal 子 DAG）。

抽为 token 并 bridge 到 `tailwind.config.js`，替换 Editor 内全部裸 hex：

```css
--color-type-condition:

124
58
237
; /* violet */
--color-type-condition-light:

245
243
255
;
--color-type-subdag:

13
148
136
; /* teal */
--color-type-subdag-light:

240
253
250
;
```

### 7.8 I — history 页裸 Tailwind 色清理

`src/pages/governance/collect-tasks/history-global/index.tsx:47,54` 的 `bg-blue-50 text-blue-700` /
`bg-slate-100 text-blue-600` → 改用现有 ds token，并入 Phase 2 色纪律清理。

### 7.9 J — 原生 `<Modal>` 收敛

`src/pages/engineering/dags/index.tsx:343,395`、`src/pages/engineering/dags/project.tsx:502` 三处原生 `<Modal>`：核对是否带
`wrapClassName="prototype-modal"`，未带则补齐（与 Modal.confirm 行为一致）。

### 7.10 G — 不纳入本轮（远期）

- **暗色模式**：`prefers-color-scheme` 全站 0 处，本轮显式声明 **light-only**。
- **命令面板 Ctrl+K**：跨页全局搜索（任务/数据源/DAG/资产），列入远期 backlog。

---

## 8. Phase 7（终扫收口）— 徽标收敛 · 对齐 · 可访问性 · 性能

> 来源：对前端工程 **第二轮全量终扫**后的收口项，与前六 Phase 构成完整方案，无遗留项。

### 8.1 K — 数据源类型色 token 化（已决策：沿用品牌色体系）

现状：`TypeBadge.tsx:5-10` 用裸 Tailwind 色
`bg-gray-50 text-blue-700 / text-indigo-700 / text-cyan-700 / text-red-700 / text-yellow-700`。

在 `tokens.css` 新增（沿用 `DatabaseTypeIcon.tsx:14-20` 的品牌色），并 bridge 到 `tailwind.config.js`：

```css
--color-type-mysql:

68
121
161
; /* MySQL #4479A1 */
--color-type-postgresql:

65
105
225
; /* PostgreSQL #4169E1 */
--color-type-doris:

30
144
255
; /* Doris #1E90FF */
--color-type-oracle:

248
0
0
; /* Oracle #F80000 */
--color-type-sqlserver:

169
29
34
; /* SQL Server #A91D22 */
```

`TypeBadge.tsx` 改用 token，与 Phase 6-H 的 DAG 类型色同属 `--color-type-*` 命名空间。数据平台按类型辨色是有意义的信息（不是装饰）。

### 8.2 K2 — 触发方式徽标收敛（新建 `src/components/TriggerBadge.tsx`）

现状：`history-common-utils.tsx:31-54` 的 `triggerBadge` 用 `bg-blue-50 text-blue-700` / `bg-violet-50 text-violet-700` /
`bg-slate-100 text-blue-600`；同款徽标在 `collect-tasks/index.tsx`、`collect-tasks/history-global`、
`sync-jobs/history-global` 被手抄成蓝 600/700、紫 600/700、slate/violet-100 等多档漂移色。

新建共享 `TriggerBadge`（MANUAL→手动 / DAG→DAG 编排 / CRON→定时，配色走 ds token + 语义色），替换上述 3~4 处副本，消灭漂移。

### 8.3 L — 数字列右对齐

现状：`text-right` 全站仅 4 处；DESIGN.md 明确"数字列右对齐"，计数/耗时/行数列目前全左对齐。

Phase 2 数据列规范补充：计数/耗时/行数/ID 列 `text-right` + `tabular-nums`（按 `COL.COUNT` / `COL.DATETIME` 列生效；操作列保持居中）。

### 8.4 O — 弹窗/抽屉可访问性（`src/components/DsModal.tsx`、`src/components/Drawer.tsx`）

现状：无 `onKeyDown`/`aria-modal`/`role="dialog"`/body 滚动锁定/focus trap。

补齐：Esc 关闭、打开时聚焦首个可聚焦元素并圈定焦点、body 滚动锁定、`role="dialog"` + `aria-modal`。

### 8.5 N — 路由 code-split（`src/router/index.tsx`）

现状：`:2-17` 除 Login/Layout 外 15 个页面全部 Eager import，单 chunk。

改为 `React.lazy` + `Suspense`（fallback 用 DsSpinner），首屏瘦身。

### 8.6 M — 双套下拉体系边界（已决策：A）

现状：原生 `<select>`（`DsFilterSelect`，11 文件）与 antd Select（8 文件）并存。

决策： **保留双体系并记录边界**——轻量筛选/分页保留原生 `DsFilterSelect`；搜索/长列表/级联（`UserSelect`/表单/编辑器等）保留
antd Select。不强行统一，避免为一致性牺牲性能与交互质量。

### 8.7 Z — arbitrary 字号收敛

现状：`text-[11px]` 8 处（TypeBadge / DsStatusBadge / alert-center / data-standards / collect / history），与 token
`ds-caption`(12px) 并存。

新增统一"badge 字号"token（11px）替换 8 处 `text-[11px]`，与 `ds-caption` 分层。

### 8.8 豁免与远期（写入文档防止遗漏）

- **P 文案/交互已达标**：按钮动词（保存/创建/删除/返回/取消）与 toast（"已保存/已删除/已创建"）风格统一，message 已收敛
  `notify`，不整改。
- **S 代码/日志暗色面豁免**：`CollectLogModal` / `NodeRuntimeLogPanel` / 编辑器内 `#64748b #e2e8f0 #1e293b`
  等属代码面，与编辑器一致，不改。
- **Q 产品向 UX（远期）**：顶栏增强（全局搜索入口/告警铃铛连告警中心/环境标识）、导出 CSV、命令面板、暗色模式 —— 同列远期
  backlog。

---

## 9. 变更文件清单

### 新增

- `docs/DataNest-UI-设计改进方案.md`（本文档）
- `src/components/LogoMark.tsx`
- `src/components/StatusSpine.tsx`
- `src/components/ErrorBoundary.tsx`
- `src/components/TriggerBadge.tsx`

### 修改

- `src/main.tsx`（ConfigProvider token 深度对齐，含 colorTextTertiary）
- `src/components/Sidebar.tsx`（图标 + LogoMark + 折叠 class）
- `src/components/Layout.tsx`（响应式 ml + ErrorBoundary 包裹）
- `src/components/SearchInput.tsx`（搜索图标）
- `src/components/Breadcrumb.tsx`（随 breadcrumb.ts 行为变化，一般无需改）
- `src/components/DsModal.tsx` / `src/components/Drawer.tsx`（可访问性：Esc/焦点圈定/滚动锁定/aria）
- `src/utils/breadcrumb.ts`（leaf 标记）
- `src/utils/format.ts`（新增 formatNumber）
- `src/styles/tokens.css`（tabular-nums / 侧栏折叠媒体查询 / :focus-visible / muted 提亮 / type 色 token / badge 字号
  token）
- `tailwind.config.js`（字体栈 + type 色 bridge）
- `src/router/index.tsx`（页面 code-split）
- `src/pages/login/index.tsx`（LogoMark + 背景母题）
- `src/pages/engineering/dags/Editor.tsx`（节点图标 + 类型色 token 化）
- `src/pages/engineering/dags/components/SqlEditorModal.tsx`（✅❌ → 图标）
- `src/pages/engineering/dags/components/PythonEditorModal.tsx`（✅❌ → 图标）
- `src/pages/engineering/dags/index.tsx` / `src/pages/engineering/dags/project.tsx`（原生 Modal 收敛）
- `src/pages/governance/metadata/lineage/LineageGraphPage.tsx`（💡 → 图标）
- `src/pages/governance/collect-tasks/history-global/index.tsx`（裸色清理 → TriggerBadge）
- `src/components/TypeBadge.tsx`（类型色 token 化）
- `src/pages/engineering/sync-jobs/history-common-utils.tsx`（triggerBadge → TriggerBadge）
- `src/pages/governance/collect-tasks/index.tsx`、`src/pages/engineering/sync-jobs/history-global/index.tsx`（触发方式徽标收敛）
- 各列表页（数据列 mono/tabular、右对齐、千分位、脊线）：`sync-jobs/index.tsx`、`sync-jobs/history-common.tsx`、
  `collect-tasks/index.tsx`、`datasources/index.tsx`、`dag-executions/index.tsx`、`metadata/index.tsx` 等

---

## 10. 验证规范

1. `pnpm typecheck`（图标改名/JSX 化、TriggerBadge 替换、路由 lazy 化后必跑）。
2. `pnpm build`。
3. 手动回归：登录页 Logo、侧边栏全部菜单与高亮、DAG 编辑器节点面板、SQL/Python
   编辑器执行结果、各列表页行/分页/数字右对齐、深层页面包屑、窗口缩至 <1024px 侧边栏折叠；antd Select 下拉/Switch/Tabs
   密度与文字、键盘 Tab 焦点环、弹窗 Esc/焦点圈定、全局渲染异常兜底页、路由懒加载首屏。
4. **首页不修改、不回归**。

---

## 11. 风险与约束

- 图标名已在 `react-icons/hi2` 中逐个核实存在（Phase 1 为机械替换，风险低）。
- Phase 3 `leaf` 只影响面包屑展示层，不影响路由与 `document.title`。
- Phase 4 侧栏折叠需给 `Sidebar` 补最小结构 class。
- 脊线/状态色复用 `--color-node-*`；新增色均有明确语义：muted 提亮（7.3）、DAG 类型色（7.7）、数据源类型色（8.1），全部以 token
  形式存在。
- 双套下拉体系为 **有意保留**（8.6），不是未收敛的缺陷。
- 本轮显式确认 **light-only**；暗色、命令面板、顶栏增强、导出 CSV 列远期。
- Phase 7 收口：审计全覆盖，无遗留建议项。
