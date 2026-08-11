# DataNest 前端开发规范

> 本文件是 AGENTS.md §9 的详细版。核心硬约束见 AGENTS.md 正文，本文件供按需查阅。

## 1. 技术栈与版本

| 层/组件 | 选型/版本 | 说明 |
|---------|-----------|------|
| 框架 | React 18.3 | 函数组件 + Hooks |
| 语言 | TypeScript ~5.6 | `strict: true` |
| 构建工具 | Vite 5.4 | 开发服务器端口 3000 |
| UI 组件库 | Ant Design 6 | 主题/样式通过 `tokens.css` 覆盖 |
| 样式 | Tailwind CSS 3.4 | 自定义 `ds-*` 设计 token |
| 路由 | React Router 6 | `createBrowserRouter` |
| 状态管理 | Zustand 5 | 当前仅 `useAuthStore` |
| HTTP | Axios 1.18 | 统一封装在 `src/api/request.ts` |
| 图标 | react-icons (Heroicons) | 统一用 `HiOutline*` 系列 |
| 代码规范 | ESLint 9 flat config | `eslint.config.js` |

## 2. 目录结构

```
src
├── api/               # 按模块封装的 API（auth.ts、sync.ts、engineering.ts...）
│   └── request.ts     # axios 统一实例 + 拦截器
├── components/        # 全局通用组件（DsButton、DsModal、Pagination...）
├── constants/         # 常量：roles.ts、datasource.ts、table.ts、statusColors.ts...
├── hooks/             # 通用 Hooks：usePagedList、useHasRole、useCanEdit、usePollingWhile
├── lib/               # 第三方封装或工具库
├── pages/             # 页面组件，按模块分 engineering/governance/system/home/login
├── router/            # 路由配置 + 路由组件（ProtectedRoute、LazyDagEditor）
├── store/             # Zustand store
├── styles/            # tokens.css（颜色唯一来源）
├── types/             # TypeScript 类型：common.ts、sync.ts、datasource.ts...
└── utils/             # 工具函数：notify.ts、error.ts、format.ts、cn.ts、download.ts...
```

**导入路径约定（2026-08-10 起）**：跨目录导入一律用别名 `@/`（`@` → `src`，vite `resolve.alias` + tsconfig `paths` 双配置），不再写 `../../` 多层相对路径；同目录兄弟文件仍用 `./`。存量代码已全量 codemod 迁移（838 处），新增代码照此执行。e2e 目录在 alias 范围外，维持相对路径。

## 3. API 请求规范

统一使用 `src/api/request.ts` 导出的 `request`：

```ts
import request from './request';

export function getSyncJob(id: string) {
    return request.get<Result<SyncJob>>(`/engineering/sync-jobs/${id}`);
}
```

约定：
- `baseURL = '/api'`，gateway 自动路由到对应服务。
- 响应拦截器校验 `code !== 200` 时统一弹错误提示并 `reject`；**不拆信封**，返回的是 `{code, message, data}` 本身。
- API 层通过 `.then(r => r.data)` 拆信封，与 `request.get<Result<T>>` / `request.post<Result<T>>` 的泛型配合。
- 需要自行处理错误时传 `{skipErrorMessage: true}`（如 SQL 预览行内展示错误、DAG 运行日志轮询）。
- 19 位 Snowflake ID 全程用 `string` 类型，**不要** `Number(id)`，避免精度丢失。
- **导出文件下载统一用 `src/utils/download.ts` 的 `downloadExportBlob`**（Sprint 8 起；2026-08-11 起导出格式为 xlsx）：`responseType:'blob'` 时业务异常的 Result JSON 会被包成 Blob、拦截器识别不了，该函数按 content-type 检出错误并弹提示，避免把错误 JSON 存成假导出文件。

## 4. 错误处理

- 普通接口错误由 `request.ts` 统一弹出 `notify.error`，页面无需重复提示。
- 需要取错误文案时用 `getErrorMessage(e)`：

```ts
import {getErrorMessage} from '../utils/error';

catch (err) {
    notify.error(getErrorMessage(err));
}
```

- 401 时拦截器自动清除 token 并跳 `/login`。

## 5. 状态管理

- 全局状态统一用 Zustand，当前只有 `useAuthStore`。
- 列表页状态不走全局 store，页面内用 `useState` + `usePagedList`。
- token / userInfo 持久化到 `localStorage`，key 名统一在 store 中定义。

## 6. 路由与权限

- 路由定义在 `src/router/index.tsx`，使用 `createBrowserRouter`。
- 需要登录的页面用 `<ProtectedRoute>` 包裹。
- 角色判断用 `useHasRole(...roles)` 或 `useCanEdit()`，角色代码从 `src/constants/roles.ts` 引入，不要硬编码字符串。
- **菜单权限唯一出处**：`src/components/Sidebar.tsx` 中的 `allMenus` + `src/constants/roles.ts` 中的角色数组；PRD/原型中的权限矩阵必须与此二者保持一致。

## 7. UI 与样式规范

- **颜色唯一来源**：`src/styles/tokens.css` `:root` 变量。新增颜色先加变量，再在 `tailwind.config.js` 桥接，不要写死 hex。
- Tailwind 使用项目自定义 token：`ds-bg-root`、`ds-text-primary`、`ds-accent`、`ds-danger` 等。
- 字体、字号、间距、圆角、阴影、z-index 等均使用 `ds-*` token。
- antd Table 统一用 `className="prototype-table prototype-table-flush"` + `pagination={false}`，分页用手写 `components/Pagination`。
- 弹窗统一用 `components/DsModal`，按钮用 `components/DsButton`，状态徽章用 `components/DsStatusBadge`。
- **弹窗 vs 抽屉分工（2026-08-08 定）**：实体的创建/编辑主表单（字段多、含配置，从列表页进入）一律用右侧 `components/Drawer`（命名 `XxxDrawer.tsx`，范本：DataSourceDrawer/SyncJobDrawer/QualityJobDrawer）；面板类查看/分析（字段血缘、执行详情）也用 Drawer。居中 `DsModal` 只用于：确认（ConfirmDialog）、轻量操作（分配、3-5 个字段以内的小表单）、聚焦代码编辑器（SQL/Python）、结果/详情查看。
- 表格列宽参考 `src/constants/table.ts` 中的 `COL`，同类列在不同页面保持相近宽度。
- **源码全部为 `.tsx`**，不要新增 `.jsx`；图标统一使用 `react-icons`（以 `HiOutline*` 系列为主）。

## 8. 列表页与分页

统一使用 `src/hooks/usePagedList.ts`：

```ts
const {list, total, page, pageSize, loading, setPage, setPageSize, applyQuery, reload} =
    usePagedList<DataSourceQuery, DataSource>({
        fetcher: async ({keyword, page, pageSize}) => {
            const result = await getDataSources({keyword, page, pageSize});
            return {list: result.data.records, total: result.data.total};
        },
        initialQuery: INITIAL_QUERY,
        defaultPageSize: 10,
    });
```

- 查询按钮调用 `applyQuery(draftQuery)`；重置按钮调用 `applyQuery(INITIAL_QUERY)`；增删改成功后调用 `reload()`。

### 页面高度策略（2026-08-07 定；2026-08-11 修订根容器写法）

Layout 视口固定（`h-screen`），按页面类型二选一，新页面必须遵守：

- **双栏/多栏结构页（树+表、左导航+内容）→ 固定撑满 + 栏内独立滚动**。根容器 `h-full flex flex-col overflow-hidden`（父链 `main > div` 已是 `flex-1 min-h-0` 确定高度；**禁用 `h-[calc(100vh-9rem)]`**——9rem 是对外壳高度的硬编码估算，外壳一变就出现底部空条；页面底部留白统一 = 主区内边距 24px，四边一致）；分栏容器 `flex-1 min-h-0 flex`；左栏卡片 `min-h-0 overflow-y-auto`；右栏卡片 `flex-1 min-h-0 overflow-hidden flex flex-col`，内部表格区 `flex-1 min-h-0 overflow-auto`，工具栏/分页器 `flex-shrink-0` 钉住。范本：元数据管理页、数据资产目录首页、质量报告页。判断依据：页面存在多个高度独立、需同时可见的区域。
  - **固定高度表格区的横向滚动条（Sprint 8 定）**：antd `scroll.x` 的横向滚动条默认跟在最后一行下面，行数不满时悬空 + 下方留白。表格区改用 `ds-table-fill flex-1 min-h-0 overflow-hidden`（tokens.css 把 antd 容器链拉伸到满高），滚动条钉在卡片底边；表格滚动条统一样式走 `.prototype-table` 细滚动条。
- **单栏列表页（工具栏+表格+分页）→ 保持整页滚动**，不强行定高（避免 flex 滚动陷阱，见 gotchas）。
- 表单/详情页 → 整页滚动。

## 9. 消息提示

统一使用 `src/utils/notify.ts`：

```ts
import {notify} from '../utils/notify';
notify.success('操作成功');
notify.error('操作失败');
```

不要直接 `import {message} from 'antd'`，避免静态 message 无法消费动态主题上下文。

## 10. 类型定义

- 后端协议类型统一放在 `src/types/common.ts`：`Result<T>`、`PageResult<T>`、`PagedQuery`。
- 各业务类型按模块分文件：`sync.ts`、`datasource.ts`、`metadata.ts` 等。
- API 函数签名使用泛型：`request.get<Result<SyncJob>>(...)`。
- **后端所有 Long 字段（不止主键，含计数/汇总值如 viewCount/refCount/total）都序列化为 string**（Sprint 8 Review 实证）：前端这些字段一律声明 `string`，展示直接用，参与比较/运算先 `Number()`。

## 11. 构建与部署

- 本地开发：`pnpm dev` / `npm run dev`（Vite dev server 端口 3000，代理 `/api` 到 `http://localhost:8080`）。
- 类型检查：`pnpm typecheck` / `npm run typecheck`。
- 构建：`pnpm build` / `npm run build`（会执行 `tsc -b && vite build`）。
- 生产部署：Docker 镜像基于 `nginx:alpine`，`dist/` 产物挂载到 `/usr/share/nginx/html/`。
- 生产构建会 `drop_console` 和 `drop_debugger`。
