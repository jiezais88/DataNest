import {createBrowserRouter} from 'react-router-dom';
import LoginPage from '../pages/login';
import Layout from '../components/Layout';
import HomePage from '../pages/home';
import UsersPage from '../pages/system/users';
import DataSourcesPage from '../pages/engineering/datasources';
import CollectTasksPage from '../pages/governance/collect-tasks';
import CollectHistoryGlobalPage from '../pages/governance/collect-tasks/history-global';
import SyncJobsPage from '../pages/engineering/sync-jobs';
import SyncJobHistoryGlobalPage from '../pages/engineering/sync-jobs/history-global';
import MetadataPage from '../pages/governance/metadata';
import LineageGraphPage from '../pages/governance/metadata/lineage/LineageGraphPage';
import AlertCenterPage from '../pages/system/alert-center/AlertCenterPage';
import DataStandardsPage from '../pages/governance/data-standards';
import DagsPage from '../pages/engineering/dags';
import ProjectDagsPage from '../pages/engineering/dags/project';
import DagExecutionsGlobalPage from '../pages/engineering/dag-executions';
import {
    CollectHistoryRedirect,
    DagExecutionsRedirect,
    LazyDagEditor,
    ProtectedRoute,
    SyncJobHistoryRedirect,
} from './components';

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
        ],
    },
]);
