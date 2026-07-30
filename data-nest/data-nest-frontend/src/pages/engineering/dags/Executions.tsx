// DAG 执行历史页
import {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {Button, Card, Empty, message, Progress, Space, Table, Tag} from 'antd';
import {ArrowLeftOutlined, ReloadOutlined, StopOutlined} from '@ant-design/icons';
import {listDagExecutions, stopDag} from './api';
import type {DagExecution} from './types';

const STATE_COLOR: Record<string, string> = {
    RUNNING: 'processing',
    SUCCESS: 'success',
    FAILED: 'error',
    TERMINATED: 'warning',
    WAITING: 'default',
    SKIPPED: 'default'
};

const STATE_LABEL: Record<string, string> = {
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    TERMINATED: '已终止',
    WAITING: '等待',
    SKIPPED: '已跳过'
};

export default function ExecutionsPage() {
    const {id} = useParams<{ id: string }>();
    const navigate = useNavigate();
    const [executions, setExecutions] = useState<DagExecution[]>([]);
    const [loading, setLoading] = useState(false);

    const refresh = async () => {
        if (!id) return;
        setLoading(true);
        try {
            const list = await listDagExecutions(Number(id));
            setExecutions(list);
        } catch (e: any) {
            message.error(e?.message || '加载失败');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        refresh();
    }, [id]);

    // 自动刷新 RUNNING
    useEffect(() => {
        const hasRunning = executions.some(e => e.status === 'RUNNING');
        if (!hasRunning) return;
        const t = setInterval(refresh, 3000);
        return () => clearInterval(t);
    }, [executions]);

    const handleStop = async (executionId: number) => {
        try {
            await stopDag(Number(id), executionId);
            message.success('已停止');
            refresh();
        } catch (e: any) {
            message.error(e?.message || '停止失败');
        }
    };

    return (
        <div className="p-6 space-y-4">
            <Card
                title="DAG 执行历史"
                extra={
                    <Space>
                        <Button icon={<ArrowLeftOutlined/>} onClick={() => navigate('/engineering/dags')}>返回</Button>
                        <Button icon={<ReloadOutlined/>} onClick={refresh}>刷新</Button>
                    </Space>
                }
            >
                {executions.length === 0 ? <Empty description="暂无执行记录"/> : (
                    <Table
                        dataSource={executions}
                        rowKey="id"
                        loading={loading}
                        pagination={{pageSize: 20}}
                        expandable={{
                            expandedRowRender: (record: DagExecution) => (
                                <Table
                                    dataSource={record.nodeExecutions || []}
                                    rowKey="id"
                                    pagination={false}
                                    size="small"
                                    columns={[
                                        {title: '节点', dataIndex: 'nodeName', width: 180},
                                        {
                                            title: '类型', dataIndex: 'nodeType', width: 80,
                                            render: (v: string) => <Tag>{v}</Tag>
                                        },
                                        {
                                            title: '状态', dataIndex: 'status', width: 100,
                                            render: (v: string) => <Tag
                                                color={STATE_COLOR[v]}>{STATE_LABEL[v] || v}</Tag>
                                        },
                                        {title: 'DS Task', dataIndex: 'dsTaskInstanceId', width: 120},
                                        {title: '开始', dataIndex: 'startTime', width: 180},
                                        {title: '结束', dataIndex: 'endTime', width: 180},
                                        {title: '耗时(ms)', dataIndex: 'durationMs', width: 100},
                                        {title: '错误', dataIndex: 'errorMessage', ellipsis: true}
                                    ]}
                                />
                            )
                        }}
                        columns={[
                            {title: 'ID', dataIndex: 'id', width: 80},
                            {title: 'DAG', dataIndex: 'dagName', width: 180},
                            {title: 'DS 实例', dataIndex: 'dsProcessInstanceId', width: 130},
                            {
                                title: '触发', dataIndex: 'triggerType', width: 80,
                                render: (v: string) => <Tag>{v}</Tag>
                            },
                            {
                                title: '状态', dataIndex: 'status', width: 100,
                                render: (v: string) => <Tag color={STATE_COLOR[v]}>{STATE_LABEL[v] || v}</Tag>
                            },
                            {title: '开始', dataIndex: 'startTime', width: 180},
                            {title: '结束', dataIndex: 'endTime', width: 180},
                            {
                                title: '耗时', dataIndex: 'durationMs', width: 90,
                                render: (v: number) => v != null ? `${v} ms` : '-'
                            },
                            {
                                title: '进度', width: 200,
                                render: (_, r: DagExecution) => {
                                    const total = r.nodeExecutions?.length || 0;
                                    if (total === 0) return '-';
                                    const done = (r.nodeExecutions || []).filter(n =>
                                        n.status === 'SUCCESS' || n.status === 'FAILED' || n.status === 'SKIPPED').length;
                                    return <Progress percent={Math.round(done / total * 100)} size="small"/>;
                                }
                            },
                            {
                                title: '操作', width: 100,
                                render: (_, r: DagExecution) => r.status === 'RUNNING' ? (
                                    <Button size="small" danger icon={<StopOutlined/>}
                                            onClick={() => handleStop(r.id!)}>停止</Button>
                                ) : null
                            }
                        ]}
                    />
                )}
            </Card>
        </div>
    );
}
