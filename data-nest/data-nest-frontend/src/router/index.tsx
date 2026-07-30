import {createBrowserRouter, Navigate} from 'react-router-dom';
import LoginPage from '../pages/login';
import Layout from '../components/Layout';
import HomePage from '../pages/home';
import UsersPage from '../pages/system/users';
import DataSourcesPage from '../pages/engineering/datasources';
import CollectTasksPage from '../pages/governance/collect-tasks';
import CollectHistoryPage from '../pages/governance/collect-tasks/history';
import CollectHistoryGlobalPage from '../pages/governance/collect-tasks/history-global';
import SyncJobsPage from '../pages/engineering/sync-jobs';
import SyncJobHistoryPage from '../pages/engineering/sync-jobs/history';
import SyncJobHistoryGlobalPage from '../pages/engineering/sync-jobs/history-global';
import MetadataPage from '../pages/governance/metadata';
import DataStandardsPage from '../pages/governance/data-standards';

const ProtectedRoute = ({children}: { children: React.ReactNode }) => {
    const token = localStorage.getItem('token');
    if (!token) return <Navigate to="/login" replace/>;
    return <>{children}</>;
};

export const router = createBrowserRouter([
    {
        path: '/login',
        element: <LoginPage/>,
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
            {path: 'engineering/sync-jobs/:syncJobId/history', element: <SyncJobHistoryPage/>},
            {path: 'engineering/sync-job-history', element: <SyncJobHistoryGlobalPage/>},
            {path: 'governance/collect-tasks', element: <CollectTasksPage/>},
            {path: 'governance/collect-tasks/:taskId/history', element: <CollectHistoryPage/>},
            {path: 'governance/collect-task-history', element: <CollectHistoryGlobalPage/>},
            {path: 'governance/metadata', element: <MetadataPage/>},
            {path: 'governance/data-standards', element: <DataStandardsPage/>},
        ],
    },
]);
