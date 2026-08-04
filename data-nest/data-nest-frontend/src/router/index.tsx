import {createBrowserRouter} from 'react-router-dom';
import LoginPage from '../pages/login';
import Layout from '../components/Layout';
import {
    CollectHistoryRedirect,
    DagExecutionsRedirect,
    LazyDagEditor,
    lazyPage,
    ProtectedRoute,
    SyncJobHistoryRedirect,
} from './components';

// 页面级 code-split（Phase 7-N）：除登录/布局外全部懒加载
const HomePage = lazyPage(() => import('../pages/home'));
const UsersPage = lazyPage(() => import('../pages/system/users'));
const DataSourcesPage = lazyPage(() => import('../pages/engineering/datasources'));
const CollectTasksPage = lazyPage(() => import('../pages/governance/collect-tasks'));
const CollectHistoryGlobalPage = lazyPage(() => import('../pages/governance/collect-tasks/history-global'));
const SyncJobsPage = lazyPage(() => import('../pages/engineering/sync-jobs'));
const SyncJobHistoryGlobalPage = lazyPage(() => import('../pages/engineering/sync-jobs/history-global'));
const MetadataPage = lazyPage(() => import('../pages/governance/metadata'));
const LineageGraphPage = lazyPage(() => import('../pages/governance/metadata/lineage/LineageGraphPage'));
const AlertCenterPage = lazyPage(() => import('../pages/system/alert-center/AlertCenterPage'));
const DataStandardsPage = lazyPage(() => import('../pages/governance/data-standards'));
const DagsPage = lazyPage(() => import('../pages/engineering/dags'));
const ProjectDagsPage = lazyPage(() => import('../pages/engineering/dags/project'));
const DagExecutionsGlobalPage = lazyPage(() => import('../pages/engineering/dag-executions'));
const QualityTemplatesPage = lazyPage(() => import('../pages/governance/quality-templates'));

export const router = createBrowserRouter([
    {
        path: '/login',
        element: <LoginPage/>,
    },
    {
        // DAG 编辑器：全屏画布（不进 Layout，无侧边栏/顶栏，对齐原型）
        path: '/engineering/dags/new',
        element: (
            <ProtectedRoute>
                <LazyDagEditor/>
            </ProtectedRoute>
        ),
    },
    {
        path: '/engineering/dags/:id/edit',
        element: (
            <ProtectedRoute>
                <LazyDagEditor/>
            </ProtectedRoute>
        ),
    },
    {
        // 执行详情画布：只读运行视图，展示某次 execution 的节点运行状态
        path: '/engineering/dags/:id/executions/:executionId',
        element: (
            <ProtectedRoute>
                <LazyDagEditor/>
            </ProtectedRoute>
        ),
    },
    {
        path: '/',
        element: (
            <ProtectedRoute>
                <Layout/>
            </ProtectedRoute>
        ),
        children: [
            {index: true, element: <HomePage/>},
            {path: 'system/users', element: <UsersPage/>},
            {path: 'engineering/datasources', element: <DataSourcesPage/>},
            {path: 'engineering/sync-jobs', element: <SyncJobsPage/>},
            {path: 'engineering/sync-jobs/:syncJobId/history', element: <SyncJobHistoryRedirect/>},
            {path: 'engineering/sync-job-history', element: <SyncJobHistoryGlobalPage/>},
            {path: 'engineering/dags', element: <DagsPage/>},
            {path: 'engineering/dags/:projectId', element: <ProjectDagsPage/>},
            {path: 'engineering/dags/:id/executions', element: <DagExecutionsRedirect/>},
            {path: 'engineering/dag-executions', element: <DagExecutionsGlobalPage/>},
            {path: 'governance/collect-tasks', element: <CollectTasksPage/>},
            {path: 'governance/collect-tasks/:taskId/history', element: <CollectHistoryRedirect/>},
            {path: 'governance/collect-task-history', element: <CollectHistoryGlobalPage/>},
            {path: 'governance/metadata', element: <MetadataPage/>},
            {path: 'governance/metadata/lineage', element: <LineageGraphPage/>},
            {path: 'system/alert-center', element: <AlertCenterPage/>},
            {path: 'governance/data-standards', element: <DataStandardsPage/>},
            {path: 'governance/quality-templates', element: <QualityTemplatesPage/>},
        ],
    },
]);
