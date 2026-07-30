// DAG 编辑器（ReactFlow 画布 + 节点配置）
import {useCallback, useEffect, useRef, useState} from 'react';
import {useNavigate, useParams, useSearchParams} from 'react-router-dom';
import ReactFlow, {
    addEdge,
    Background,
    type Connection,
    Controls,
    type Edge,
    Handle,
    MiniMap,
    type Node,
    Position,
    useEdgesState,
    useNodesState
} from 'reactflow';
import 'reactflow/dist/style.css';
import {Button, Card, Drawer, Form, Input, message, Radio, Select, Space, Switch, Tag} from 'antd';
import {ArrowLeftOutlined, PlayCircleOutlined, SaveOutlined} from '@ant-design/icons';
import {createDag, getDag, triggerDag, updateDag} from './api';
import type {Dag, NodeType} from './types';
import axios from 'axios';

// 同步任务列表（从 syncJob 拿）
const listSyncJobs = async () => {
    const r = await axios.get('/api/engineering/sync-jobs', {params: {page: 1, pageSize: 1000}});
    return r.data?.records || [];
};

type RFNodeData = {
    nodeName: string;
    nodeType: NodeType;
    sqlContent?: string;
    syncJobId?: number;
    syncJobName?: string;
};

// 自定义 SQL 节点
function SqlNode({data, selected}: { data: RFNodeData; selected: boolean }) {
    return (
        <div style={{
            background: '#e6f4ff', border: `2px solid ${selected ? '#1677ff' : '#91caff'}`,
            borderRadius: 8, padding: 10, width: 200, fontSize: 12
        }}>
            <Handle type="target" position={Position.Left} style={{background: '#1677ff'}}/>
            <div style={{fontWeight: 600, marginBottom: 4}}>
                <Tag color="blue">SQL</Tag> {data.nodeName}
            </div>
            <div style={{
                color: '#666',
                fontSize: 11,
                fontFamily: 'monospace',
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis'
            }}>
                {(data.sqlContent || '').slice(0, 40) || '（未配置 SQL）'}
            </div>
            <Handle type="source" position={Position.Right} style={{background: '#1677ff'}}/>
        </div>
    );
}

function SyncNode({data, selected}: { data: RFNodeData; selected: boolean }) {
    return (
        <div style={{
            background: '#f6ffed', border: `2px solid ${selected ? '#52c41a' : '#b7eb8f'}`,
            borderRadius: 8, padding: 10, width: 200, fontSize: 12
        }}>
            <Handle type="target" position={Position.Left} style={{background: '#52c41a'}}/>
            <div style={{fontWeight: 600, marginBottom: 4}}>
                <Tag color="green">SYNC</Tag> {data.nodeName}
            </div>
            <div style={{color: '#666', fontSize: 11}}>
                同步任务：{data.syncJobName || data.syncJobId || '（未选择）'}
            </div>
            <Handle type="source" position={Position.Right} style={{background: '#52c41a'}}/>
        </div>
    );
}

const nodeTypes = {SQL: SqlNode, SYNC: SyncNode};

export default function DagEditor() {
    const {id} = useParams<{ id: string }>();
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const isNew = !id || id === 'new';
    const projectId = Number(searchParams.get('projectId')) || 0;

    const [rfNodes, setRfNodes, onNodesChange] = useNodesState<Node<RFNodeData>>([]);
    const [rfEdges, setRfEdges, onEdgesChange] = useEdgesState<Edge>([]);
    const [dag, setDag] = useState<Dag>({
        projectId,
        name: '',
        triggerType: 'MANUAL',
        cronExpression: '',
        scheduleEnabled: false,
        maxParallelism: 3,
        status: 'ENABLED',
        nodes: [],
        edges: []
    });
    const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
    const [drawerOpen, setDrawerOpen] = useState(false);
    const [syncJobs, setSyncJobs] = useState<any[]>([]);
    const [form] = Form.useForm();
    const nodeIdRef = useRef(0);
    const edgeIdRef = useRef(0);

    // 加载已有 DAG
    useEffect(() => {
        if (!isNew && id) {
            getDag(Number(id)).then(d => {
                setDag(d);
                const rfnodes: Node<RFNodeData>[] = (d.nodes || []).map(n => {
                    const cfg = parseConfig(n.config);
                    return {
                        id: n.nodeId,
                        type: n.nodeType,
                        position: {x: n.positionX || 0, y: n.positionY || 0},
                        data: {
                            nodeName: n.nodeName,
                            nodeType: n.nodeType,
                            sqlContent: cfg.sqlContent,
                            syncJobId: cfg.syncJobId,
                            syncJobName: cfg.syncJobName
                        }
                    };
                });
                const rfedges: Edge[] = (d.edges || []).map(e => ({
                    id: e.edgeId,
                    source: e.sourceNodeId,
                    target: e.targetNodeId,
                    animated: true
                }));
                setRfNodes(rfnodes);
                setRfEdges(rfedges);
                nodeIdRef.current = rfnodes.length;
                edgeIdRef.current = rfedges.length;
            }).catch(e => message.error('加载 DAG 失败: ' + (e?.message || '')));
        }
    }, [id]);

    // 加载同步任务列表
    useEffect(() => {
        listSyncJobs().then(setSyncJobs).catch(() => {
        });
    }, []);

    const onConnect = useCallback((params: Connection) => {
        if (!params.source || !params.target) return;
        const newEdge: Edge = {
            id: `e${++edgeIdRef.current}_${Date.now()}`,
            source: params.source,
            target: params.target,
            animated: true
        };
        setRfEdges(eds => addEdge(newEdge, eds));
    }, [setRfEdges]);

    const handleAddNode = (type: NodeType) => {
        const newId = `n${++nodeIdRef.current}_${Date.now()}`;
        const newNode: Node<RFNodeData> = {
            id: newId,
            type,
            position: {x: 100 + Math.random() * 200, y: 100 + Math.random() * 200},
            data: {
                nodeName: type === 'SQL' ? 'SQL 任务' : '同步任务',
                nodeType: type,
                sqlContent: type === 'SQL' ? '-- 在右侧配置 SQL\nSELECT 1;' : undefined,
                syncJobId: type === 'SYNC' ? syncJobs[0]?.id : undefined,
                syncJobName: type === 'SYNC' ? syncJobs[0]?.name : undefined
            }
        };
        setRfNodes(ns => [...ns, newNode]);
    };

    const handleNodeDoubleClick = (_: any, node: Node<RFNodeData>) => {
        setSelectedNodeId(node.id);
        form.setFieldsValue({
            nodeId: node.id,
            nodeName: node.data.nodeName,
            sqlContent: node.data.sqlContent || '',
            syncJobId: node.data.syncJobId
        });
        setDrawerOpen(true);
    };

    const handleSaveNode = async () => {
        const v = await form.validateFields();
        const node = rfNodes.find(n => n.id === selectedNodeId);
        if (!node) return;
        const syncJob = syncJobs.find((j: any) => j.id === v.syncJobId);
        setRfNodes(ns => ns.map(n => n.id === selectedNodeId ? {
            ...n,
            data: {
                ...n.data,
                nodeName: v.nodeName,
                sqlContent: v.sqlContent,
                syncJobId: v.syncJobId,
                syncJobName: syncJob?.name
            }
        } : n));
        setDrawerOpen(false);
    };

    const handleDeleteNode = (nodeId: string) => {
        setRfNodes(ns => ns.filter(n => n.id !== nodeId));
        setRfEdges(es => es.filter(e => e.source !== nodeId && e.target !== nodeId));
    };

    const handleSave = async () => {
        if (!dag.name) {
            message.error('DAG 名称必填');
            return;
        }
        if (rfNodes.length === 0) {
            message.error('至少一个节点');
            return;
        }
        const payload: Dag = {
            ...dag,
            nodes: rfNodes.map(n => ({
                nodeId: n.id,
                nodeName: n.data.nodeName,
                nodeType: n.data.nodeType,
                positionX: n.position.x,
                positionY: n.position.y,
                config: serializeConfig(n.data)
            })),
            edges: rfEdges.map(e => ({
                edgeId: e.id,
                sourceNodeId: e.source,
                targetNodeId: e.target
            }))
        };
        try {
            let savedId = id;
            if (isNew) {
                const created = await createDag(payload);
                savedId = String(created.id);
                message.success('DAG 已创建');
            } else {
                await updateDag(Number(id), payload);
                message.success('DAG 已更新');
            }
            navigate(`/engineering/dags/${savedId}/edit`);
        } catch (e: any) {
            message.error(e?.message || '保存失败');
        }
    };

    const handleTrigger = async () => {
        if (!id) {
            message.warning('请先保存');
            return;
        }
        try {
            await triggerDag(Number(id));
            message.success('触发成功');
        } catch (e: any) {
            message.error(e?.message || '触发失败');
        }
    };

    return (
        <div className="h-screen flex flex-col">
            {/* 顶部工具栏 */}
            <Card size="small" className="m-2" styles={{body: {padding: 12}}}>
                <Space>
                    <Button icon={<ArrowLeftOutlined/>} onClick={() => navigate('/engineering/dags')}>返回</Button>
                    <Input
                        placeholder="DAG 名称"
                        value={dag.name}
                        onChange={e => setDag({...dag, name: e.target.value})}
                        style={{width: 220}}
                    />
                    <Radio.Group value={dag.triggerType} onChange={e => setDag({...dag, triggerType: e.target.value})}>
                        <Radio.Button value="MANUAL">手动</Radio.Button>
                        <Radio.Button value="CRON">定时</Radio.Button>
                    </Radio.Group>
                    {dag.triggerType === 'CRON' && (
                        <Input
                            placeholder="0 0 2 * * ?"
                            value={dag.cronExpression || ''}
                            onChange={e => setDag({...dag, cronExpression: e.target.value})}
                            style={{width: 180}}
                        />
                    )}
                    <span>启用</span>
                    <Switch checked={dag.status === 'ENABLED'}
                            onChange={v => setDag({...dag, status: v ? 'ENABLED' : 'DISABLED'})}/>
                    <span style={{flex: 1}}/>
                    <Button icon={<PlayCircleOutlined/>} onClick={handleTrigger}
                            disabled={isNew || rfNodes.length === 0}>
                        触发
                    </Button>
                    <Button type="primary" icon={<SaveOutlined/>} onClick={handleSave}>
                        保存
                    </Button>
                </Space>
            </Card>

            <div className="flex-1 flex">
                {/* 左侧：节点添加 */}
                <Card size="small" style={{width: 180, margin: '0 0 8px 8px'}} title="添加节点">
                    <Space direction="vertical" className="w-full">
                        <Button block onClick={() => handleAddNode('SQL')} type="primary" ghost>SQL 节点</Button>
                        <Button block onClick={() => handleAddNode('SYNC')} type="primary" ghost>同步节点</Button>
                    </Space>
                </Card>

                {/* 中间：画布 */}
                <div style={{flex: 1, margin: '0 8px 8px 0'}}>
                    <ReactFlow
                        nodes={rfNodes}
                        edges={rfEdges}
                        onNodesChange={onNodesChange}
                        onEdgesChange={onEdgesChange}
                        onConnect={onConnect}
                        onNodeDoubleClick={handleNodeDoubleClick}
                        nodeTypes={nodeTypes}
                        fitView
                    >
                        <Background/>
                        <Controls/>
                        <MiniMap/>
                    </ReactFlow>
                </div>
            </div>

            {/* 右侧：节点配置抽屉 */}
            <Drawer
                title="节点配置"
                open={drawerOpen}
                onClose={() => setDrawerOpen(false)}
                width={520}
                extra={selectedNodeId && (
                    <Button danger onClick={() => {
                        handleDeleteNode(selectedNodeId);
                        setDrawerOpen(false);
                    }}>删除</Button>
                )}
            >
                <Form form={form} layout="vertical">
                    <Form.Item label="节点 ID" name="nodeId"><Input disabled/></Form.Item>
                    <Form.Item label="节点名称" name="nodeName" rules={[{required: true}]}>
                        <Input/>
                    </Form.Item>
                    {rfNodes.find(n => n.id === selectedNodeId)?.data.nodeType === 'SQL' ? (
                        <Form.Item label="SQL 内容" name="sqlContent"
                                   rules={[{required: true, message: 'SQL 节点必须配置 SQL 内容'}]}>
                            <Input.TextArea rows={16} placeholder="-- 在此输入 SQL，多条用 ; 分隔
SELECT id, name FROM users;
INSERT INTO ..." style={{fontFamily: 'monospace'}}/>
                        </Form.Item>
                    ) : (
                        <Form.Item label="同步任务" name="syncJobId" rules={[{required: true}]}>
                            <Select
                                showSearch
                                placeholder="选择同步任务"
                                optionFilterProp="label"
                                options={syncJobs.map((j: any) => ({label: `${j.id} - ${j.name}`, value: j.id}))}
                            />
                        </Form.Item>
                    )}
                    <Button type="primary" block onClick={handleSaveNode}>保存节点</Button>
                </Form>
            </Drawer>
        </div>
    );
}

function parseConfig(config?: string): { sqlContent?: string; syncJobId?: number; syncJobName?: string } {
    if (!config) return {};
    try {
        return JSON.parse(config);
    } catch {
        return {};
    }
}

function serializeConfig(data: RFNodeData): string {
    if (data.nodeType === 'SQL') {
        return JSON.stringify({type: 'SQL', sqlContent: data.sqlContent || ''});
    }
    return JSON.stringify({type: 'SYNC', syncJobId: data.syncJobId, syncJobName: data.syncJobName});
}
