import {useCallback, useEffect, useMemo, useState} from 'react';
import type {AutoTriggerObjectType} from '../../../types/quality';
import {
    getDag,
    listDagProjects,
    listDags,
} from '../../engineering/dags/api';
import {querySyncJobs} from '../../../api/sync';
import {queryCollectTasks} from '../../../api/collect';
import type {DagProject} from '../../engineering/dags/types';

interface AutoTriggerSelectProps {
    /** 当前对象类型（可为空表示未选） */
    objectType: AutoTriggerObjectType | '';
    /** 当前对象 ID */
    objectId: string;
    onChange: (type: AutoTriggerObjectType | '', id: string, name: string) => void;
    readOnly?: boolean;
}

const OBJECT_TYPE_OPTIONS: { value: AutoTriggerObjectType; label: string }[] = [
    {value: 'DAG_NODE', label: 'DAG 节点'},
    {value: 'SYNC_JOB', label: '同步任务'},
    {value: 'COLLECT_TASK', label: '采集任务'},
];

interface DagNodeOption {
    id: number;
    nodeName: string;
    nodeId: string;
}

/**
 * 自动触发绑定对象选择器。
 * - DAG_NODE：项目 → DAG → 节点 三级级联（存 dag_node 主键 id）
 * - SYNC_JOB：同步任务下拉（存 sync_job.id）
 * - COLLECT_TASK：采集任务下拉（存 collect_task.id）
 *
 * 性能说明：DAG_NODE 编辑回显需按目标节点反查所在项目/DAG。
 * 后端无「按节点 id 反查」接口，只能遍历项目→DAG→getDag 详情找目标节点。
 * 为控制成本，restoreDagNode 复用已加载的 projects state（不重拉），
 * 并用 Promise.all 并行 getDag（而非串行 await），命中即停止后续查询。
 */
export default function AutoTriggerSelect({
                                              objectType,
                                              objectId,
                                              onChange,
                                              readOnly = false,
                                          }: AutoTriggerSelectProps) {
    // DAG 节点三级级联
    const [projects, setProjects] = useState<DagProject[]>([]);
    const [projectId, setProjectId] = useState<string>('');
    const [dags, setDags] = useState<{ id: string; name: string }[]>([]);
    const [dagId, setDagId] = useState<string>('');
    const [nodes, setNodes] = useState<DagNodeOption[]>([]);
    const [nodeId, setNodeId] = useState<string>('');
    const [dagLoading, setDagLoading] = useState(false);

    // 同步任务 / 采集任务下拉
    const [syncJobs, setSyncJobs] = useState<{ id: string; name: string }[]>([]);
    const [collectTasks, setCollectTasks] = useState<{ id: string; name: string }[]>([]);

    // 编辑回显：根据 objectType 加载对应对象名（DAG_NODE 需反查项目/DAG）
    useEffect(() => {
        if (readOnly || !objectType) return;
        if (objectType === 'SYNC_JOB') {
            loadSyncJobs().then((list) => {
                const hit = list.find((j) => String(j.id) === String(objectId));
                if (hit) onChange(objectType, String(hit.id), hit.name);
            });
        } else if (objectType === 'COLLECT_TASK') {
            loadCollectTasks().then((list) => {
                const hit = list.find((t) => String(t.id) === String(objectId));
                if (hit) onChange(objectType, String(hit.id), hit.name);
            });
        } else if (objectType === 'DAG_NODE' && objectId) {
            restoreDagNode(objectId);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [readOnly, objectType, objectId]);

    // 加载项目列表
    useEffect(() => {
        if (readOnly || objectType !== 'DAG_NODE') return;
        listDagProjects({page: 1, pageSize: 1000})
            .then((res) => setProjects(res.records || []))
            .catch(() => setProjects([]));
    }, [readOnly, objectType]);

    // 项目变化 → 加载 DAG
    useEffect(() => {
        if (!projectId) {
            setDags([]);
            setDagId('');
            return;
        }
        setDagLoading(true);
        listDags(projectId)
            .then((res) => {
                setDags(res.map((d) => ({id: String(d.id), name: d.name})));
            })
            .catch(() => setDags([]))
            .finally(() => setDagLoading(false));
        setDagId('');
        setNodes([]);
        setNodeId('');
    }, [projectId]);

    // DAG 变化 → 加载节点
    useEffect(() => {
        if (!dagId) {
            setNodes([]);
            setNodeId('');
            return;
        }
        setDagLoading(true);
        getDag(dagId)
            .then((res) => {
                setNodes((res.nodes || []).map((n) => ({
                    id: n.id ?? 0,
                    nodeName: n.nodeName,
                    nodeId: n.nodeId,
                })));
            })
            .catch(() => setNodes([]))
            .finally(() => setDagLoading(false));
        setNodeId('');
    }, [dagId]);

    const loadSyncJobs = async () => {
        try {
            const res = await querySyncJobs({page: 1, pageSize: 1000});
            const list = (res.data.records || []).map((j) => ({id: String(j.id), name: j.name}));
            setSyncJobs(list);
            return list;
        } catch {
            setSyncJobs([]);
            return [];
        }
    };

    const loadCollectTasks = async () => {
        try {
            const res = await queryCollectTasks({page: 1, pageSize: 1000});
            const list = (res.data.records || []).map((t) => ({id: String(t.id), name: t.name}));
            setCollectTasks(list);
            return list;
        } catch {
            setCollectTasks([]);
            return [];
        }
    };

    const restoreDagNode = useCallback(async (targetId: string) => {
        try {
            // 复用已加载的 projects（避免重拉）；为空时先拉一次
            let projList = projects;
            if (!projList.length) {
                const res = await listDagProjects({page: 1, pageSize: 1000});
                projList = res.records || [];
                setProjects(projList);
            }
            // 并行拉取各项目 DAG 列表
            const allDags = await Promise.all(
                projList.map(async (p) => ({
                    project: p,
                    dags: await listDags(String(p.id)).catch(() => []),
                }))
            );
            // 并行拉取各 DAG 节点反查目标节点；全量完成后取命中项（lib 不支持 Promise.any，用 Promise.all + find）
            const hits = await Promise.all(
                allDags.flatMap(({project, dags}) =>
                    dags.map(async (d) => {
                        const detail = await getDag(String(d.id)).catch(() => null);
                        if (!detail) return null;
                        const match = (detail.nodes || []).find((n) => String(n.id) === String(targetId));
                        if (!match) return null;
                        return {project, dags: dags.map((x) => ({id: String(x.id), name: x.name})), dag: d, detail, match};
                    })
                )
            );
            const found = hits.find((h): h is NonNullable<typeof h> => h !== null);
            if (!found) return;
            setProjectId(String(found.project.id));
            setDagId(String(found.dag.id));
            setDags(found.dags);
            setNodes((found.detail.nodes || []).map((n) => ({
                id: n.id ?? 0,
                nodeName: n.nodeName,
                nodeId: n.nodeId,
            })));
            setNodeId(String(found.match.id));
        } catch {
            // 未找到目标节点或请求失败，保持为空
        }
    }, [projects]);

    const handleTypeChange = (type: AutoTriggerObjectType | '') => {
        onChange(type, '', '');
        setProjectId('');
        setDagId('');
        setNodeId('');
    };

    const selectClass = 'w-full px-ds-3 py-ds-2 bg-white border border-ds-border-subtle rounded-ds-sm text-ds-small text-ds-text-primary focus:outline-none focus-visible:border-ds-accent disabled:opacity-60 disabled:cursor-not-allowed';

    const objectTypeOptions = useMemo(() => OBJECT_TYPE_OPTIONS, []);

    return (
        <div className="space-y-ds-3">
            <div>
                <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                    绑定对象类型
                </label>
                <select
                    value={objectType}
                    onChange={(e) => handleTypeChange(e.target.value as AutoTriggerObjectType | '')}
                    disabled={readOnly}
                    className={selectClass}
                >
                    <option value="">请选择对象类型</option>
                    {objectTypeOptions.map((o) => (
                        <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                </select>
            </div>

            {objectType === 'DAG_NODE' && (
                <>
                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            项目 <span className="text-ds-danger">*</span>
                        </label>
                        <select
                            value={projectId}
                            onChange={(e) => setProjectId(e.target.value)}
                            disabled={readOnly}
                            className={selectClass}
                        >
                            <option value="">请选择项目</option>
                            {projects.map((p) => (
                                <option key={String(p.id)} value={String(p.id)}>{p.name}</option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            DAG <span className="text-ds-danger">*</span>
                        </label>
                        <select
                            value={dagId}
                            onChange={(e) => setDagId(e.target.value)}
                            disabled={readOnly || !projectId}
                            className={selectClass}
                        >
                            <option value="">{dagLoading ? '加载中...' : '请选择 DAG'}</option>
                            {dags.map((d) => (
                                <option key={d.id} value={d.id}>{d.name}</option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                            节点 <span className="text-ds-danger">*</span>
                        </label>
                        <select
                            value={nodeId}
                            onChange={(e) => {
                                const v = e.target.value;
                                setNodeId(v);
                                const hit = nodes.find((n) => String(n.id) === String(v));
                                if (hit) onChange('DAG_NODE', String(hit.id), hit.nodeName);
                            }}
                            disabled={readOnly || !dagId}
                            className={selectClass}
                        >
                            <option value="">{dagLoading ? '加载中...' : '请选择节点'}</option>
                            {nodes.map((n) => (
                                <option key={n.id} value={String(n.id)}>{n.nodeName}</option>
                            ))}
                        </select>
                    </div>
                </>
            )}

            {objectType === 'SYNC_JOB' && (
                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        同步任务 <span className="text-ds-danger">*</span>
                    </label>
                    <select
                        value={objectId}
                        onChange={(e) => {
                            const v = e.target.value;
                            const hit = syncJobs.find((j) => String(j.id) === String(v));
                            onChange('SYNC_JOB', v, hit?.name || '');
                        }}
                        disabled={readOnly}
                        className={selectClass}
                    >
                        <option value="">请选择同步任务</option>
                        {syncJobs.map((j) => (
                            <option key={j.id} value={j.id}>{j.name}</option>
                        ))}
                    </select>
                </div>
            )}

            {objectType === 'COLLECT_TASK' && (
                <div>
                    <label className="block text-ds-small font-semibold text-ds-text-secondary mb-ds-1.5">
                        采集任务 <span className="text-ds-danger">*</span>
                    </label>
                    <select
                        value={objectId}
                        onChange={(e) => {
                            const v = e.target.value;
                            const hit = collectTasks.find((t) => String(t.id) === String(v));
                            onChange('COLLECT_TASK', v, hit?.name || '');
                        }}
                        disabled={readOnly}
                        className={selectClass}
                    >
                        <option value="">请选择采集任务</option>
                        {collectTasks.map((t) => (
                            <option key={t.id} value={t.id}>{t.name}</option>
                        ))}
                    </select>
                </div>
            )}
        </div>
    );
}
