import {createBrowserRouter, Navigate} from 'react-router-dom';
import LoginPage from '../pages/login';
import Layout from '../components/Layout';
import HomePage from '../pages/home';
import UsersPage from '../pages/system/users';
import DataSourcesPage from '../pages/engineering/datasources';
import CollectTasksPage from '../pages/governance/collect-tasks';
import CollectHistoryPage from '../pages/governance/collect-tasks/history';
import MetadataPage from '../pages/governance/metadata';

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
            {path: 'governance/collect-tasks', element: <CollectTasksPage/>},
            {path: 'governance/collect-tasks/:taskId/history', element: <CollectHistoryPage/>},
            {path: 'governance/metadata', element: <MetadataPage/>},
        ],
    },
]);
