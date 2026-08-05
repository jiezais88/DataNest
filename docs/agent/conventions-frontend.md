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
└── utils/             # 工具函数：notify.ts、error.ts、format.ts、cn.ts...
```

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

## 11. 构建与部署

- 本地开发：`pnpm dev` / `npm run dev`（Vite dev server 端口 3000，代理 `/api` 到 `http://localhost:8080`）。
- 类型检查：`pnpm typecheck` / `npm run typecheck`。
- 构建：`pnpm build` / `npm run build`（会执行 `tsc -b && vite build`）。
- 生产部署：Docker 镜像基于 `nginx:alpine`，`dist/` 产物挂载到 `/usr/share/nginx/html/`。
- 生产构建会 `drop_console` 和 `drop_debugger`。
