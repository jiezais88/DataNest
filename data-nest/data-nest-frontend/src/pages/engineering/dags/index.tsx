// DAG 项目 + DAG 列表页
import {useEffect, useState} from 'react';
import {Button, Card, Empty, Form, Input, message, Modal, Popconfirm, Select, Space, Table, Tag} from 'antd';
import {
    CodeOutlined,
    DeleteOutlined,
    EditOutlined,
    HistoryOutlined,
    PlayCircleOutlined,
    PlusOutlined
} from '@ant-design/icons';
import {useNavigate} from 'react-router-dom';
import {
    createDagProject,
    deleteDag,
    deleteDagProject,
    listDagProjects,
    listDags,
    triggerDag,
    updateDagProject
} from './api';
import type {Dag, DagProject} from './types';

export default function DagsPage() {
    const navigate = useNavigate();
    const [projects, setProjects] = useState<DagProject[]>([]);
    const [selectedProjectId, setSelectedProjectId] = useState<number | undefined>(undefined);
    const [dags, setDags] = useState<Dag[]>([]);
    const [projectModalOpen, setProjectModalOpen] = useState(false);
    const [editingProject, setEditingProject] = useState<DagProject | null>(null);
    const [projectForm] = Form.useForm();

    const refreshProjects = async () => {
        const list = await listDagProjects();
        setProjects(list);
        if (list.length > 0 && !selectedProjectId) {
            setSelectedProjectId(list[0].id);
        }
    };

    const refreshDags = async () => {
        if (!selectedProjectId) {
            setDags([]);
            return;
        }
        const list = await listDags(selectedProjectId);
        setDags(list);
    };

    useEffect(() => {
        refreshProjects();
    }, []);
    useEffect(() => {
        refreshDags();
    }, [selectedProjectId]);

    const handleSaveProject = async () => {
        const values = await projectForm.validateFields();
        try {
            if (editingProject?.id) {
                await updateDagProject(editingProject.id, values);
                message.success('项目已更新');
            } else {
                await createDagProject(values);
                message.success('项目已创建');
            }
            setProjectModalOpen(false);
            setEditingProject(null);
            projectForm.resetFields();
            await refreshProjects();
        } catch (e: any) {
            message.error(e?.message || '操作失败');
        }
    };

    const handleDeleteProject = async (id: number) => {
        try {
            await deleteDagProject(id);
            message.success('项目已删除');
            if (selectedProjectId === id) setSelectedProjectId(undefined);
            await refreshProjects();
        } catch (e: any) {
            message.error(e?.message || '删除失败');
        }
    };

    const handleDeleteDag = async (id: number) => {
        try {
            await deleteDag(id);
            message.success('DAG 已删除');
            await refreshDags();
        } catch (e: any) {
            message.error(e?.message || '删除失败');
        }
    };

    const handleTrigger = async (id: number) => {
        try {
            const exec = await triggerDag(id);
            message.success(`触发成功，executionId=${exec.id}`);
            navigate(`/engineering/dags/${id}/executions`);
        } catch (e: any) {
            message.error(e?.message || '触发失败');
        }
    };

    return (
        <div className="p-6 space-y-4">
            <Card title="DAG 项目" extra={
                <Space>
                    <Select
                        placeholder="选择项目"
                        value={selectedProjectId}
                        onChange={setSelectedProjectId}
                        style={{width: 220}}
                        options={projects.map(p => ({label: p.name, value: p.id}))}
                    />
                    <Button type="primary" icon={<PlusOutlined/>}
                            onClick={() => {
                                setEditingProject(null);
                                projectForm.resetFields();
                                setProjectModalOpen(true);
                            }}>
                        新建项目
                    </Button>
                </Space>
            }>
                <Table
                    dataSource={projects}
                    rowKey="id"
                    pagination={false}
                    size="small"
                    columns={[
                        {title: 'ID', dataIndex: 'id', width: 60},
                        {title: '名称', dataIndex: 'name'},
                        {title: '描述', dataIndex: 'description', ellipsis: true},
                        {title: '创建时间', dataIndex: 'createdAt', width: 180},
                        {
                            title: '操作', width: 180,
                            render: (_, r: DagProject) => (
                                <Space>
                                    <Button size="small" icon={<EditOutlined/>}
                                            onClick={() => {
                                                setEditingProject(r);
                                                projectForm.setFieldsValue(r);
                                                setProjectModalOpen(true);
                                            }}>
                                        编辑
                                    </Button>
                                    <Popconfirm title="确认删除？" onConfirm={() => handleDeleteProject(r.id!)}>
                                        <Button size="small" danger icon={<DeleteOutlined/>}>删除</Button>
                                    </Popconfirm>
                                </Space>
                            )
                        }
                    ]}
                />
            </Card>

            <Card title={`DAG 列表 ${selectedProjectId ? '' : '(请先选项目)'}`} extra={
                <Button type="primary" icon={<PlusOutlined/>} disabled={!selectedProjectId}
                        onClick={() => navigate(`/engineering/dags/new?projectId=${selectedProjectId}`)}>
                    新建 DAG
                </Button>
            }>
                {dags.length === 0 ? <Empty description="暂无 DAG"/> : (
                    <Table
                        dataSource={dags}
                        rowKey="id"
                        pagination={false}
                        columns={[
                            {title: 'ID', dataIndex: 'id', width: 60},
                            {title: '名称', dataIndex: 'name'},
                            {
                                title: '触发', dataIndex: 'triggerType', width: 90,
                                render: (v: string) => <Tag color={v === 'CRON' ? 'blue' : 'default'}>{v}</Tag>
                            },
                            {title: 'Cron', dataIndex: 'cronExpression', width: 120, ellipsis: true},
                            {
                                title: '状态', dataIndex: 'status', width: 90,
                                render: (v: string) => <Tag color={v === 'ENABLED' ? 'green' : 'default'}>{v}</Tag>
                            },
                            {title: 'DS Code', dataIndex: 'dsProcessDefinitionCode', width: 140},
                            {
                                title: '发布', dataIndex: 'releaseState', width: 100,
                                render: (v: string) => v ?
                                    <Tag color={v === 'ONLINE' ? 'green' : 'orange'}>{v}</Tag> : '-'
                            },
                            {
                                title: '操作', width: 280, fixed: 'right',
                                render: (_, r: Dag) => (
                                    <Space>
                                        <Button size="small" type="primary" icon={<PlayCircleOutlined/>}
                                                onClick={() => handleTrigger(r.id!)}>触发</Button>
                                        <Button size="small" icon={<CodeOutlined/>}
                                                onClick={() => navigate(`/engineering/dags/${r.id}/edit`)}>编辑</Button>
                                        <Button size="small" icon={<HistoryOutlined/>}
                                                onClick={() => navigate(`/engineering/dags/${r.id}/executions`)}>历史</Button>
                                        <Popconfirm title="确认删除？" onConfirm={() => handleDeleteDag(r.id!)}>
                                            <Button size="small" danger icon={<DeleteOutlined/>}>删除</Button>
                                        </Popconfirm>
                                    </Space>
                                )
                            }
                        ]}
                    />
                )}
            </Card>

            <Modal
                title={editingProject ? '编辑 DAG 项目' : '新建 DAG 项目'}
                open={projectModalOpen}
                onCancel={() => {
                    setProjectModalOpen(false);
                    setEditingProject(null);
                    projectForm.resetFields();
                }}
                onOk={handleSaveProject}
                okText="保存"
                cancelText="取消"
            >
                <Form form={projectForm} layout="vertical">
                    <Form.Item label="名称" name="name" rules={[{required: true, max: 100}]}>
                        <Input placeholder="如 data-dev"/>
                    </Form.Item>
                    <Form.Item label="描述" name="description" rules={[{max: 1000}]}>
                        <Input.TextArea rows={3} placeholder="项目描述（可选）"/>
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    );
}
