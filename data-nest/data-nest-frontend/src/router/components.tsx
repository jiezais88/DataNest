import {lazy, Suspense} from 'react';
import {Navigate, useParams} from 'react-router-dom';
import DsSpinner from '../components/DsSpinner';

// DAG 编辑器整页懒加载：画布依赖 ReactFlow + Monaco（SQL 编辑器），
// 体积约 4MB，只应在这三个画布路由进入时下载
const DagEditor = lazy(() => import('../pages/engineering/dags/Editor'));

export function LazyDagEditor() {
    return (
        <Suspense fallback={
            <div className="min-h-screen bg-ds-bg-root flex items-center justify-center">
                <DsSpinner size={20}/>
            </div>
        }>
            <DagEditor/>
        </Suspense>
    );
}

// 旧 per-task 历史页已合并进全局历史页（?id= 精确过滤），老链接 301 到全局页
export function SyncJobHistoryRedirect() {
    const {syncJobId} = useParams();
    return <Navigate to={`/engineering/sync-job-history?syncJobId=${syncJobId}`} replace/>;
}

export function CollectHistoryRedirect() {
    const {taskId} = useParams();
    return <Navigate to={`/governance/collect-task-history?taskId=${taskId}`} replace/>;
}

export function DagExecutionsRedirect() {
    const {id} = useParams();
    return <Navigate to={`/engineering/dag-executions?dagId=${id}`} replace/>;
}

export const ProtectedRoute = ({children}: { children: React.ReactNode }) => {
    const token = localStorage.getItem('token');
    if (!token) return <Navigate to="/login" replace/>;
    return <>{children}</>;
};
