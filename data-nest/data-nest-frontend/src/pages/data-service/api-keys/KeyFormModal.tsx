// API Key 新建/编辑弹窗（Sprint 10 F2）。
// 创建成功后切换为「明文一次性展示」视图（Key 仅本次可见，后端只存 SHA-256 哈希）。
import {useEffect, useMemo, useState} from 'react';
import {HiOutlineKey, HiOutlineClipboardDocument} from 'react-icons/hi2';
import {notify} from '@/utils/notify';
import {getErrorMessage} from '@/utils/error';
import {pageCdcPipelines} from '@/api/cdc';
import {createApiKey, getApiKey, pageDataApis, updateApiKey} from '@/api/data-service';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import DsSpinner from '@/components/DsSpinner';
import DsStatusBadge from '@/components/DsStatusBadge';
import {DataApiStatusBadge} from '../badges';
import type {CdcPipeline} from '@/types/cdc';
import type {ApiKeyCreateResult, ApiKeyPageItem, DataApiPageItem} from '@/types/data-service';

const INPUT_CLASS =
    'w-full px-ds-3 py-ds-2 border border-ds-border-subtle rounded-ds-sm text-ds-body focus:outline-none focus:border-ds-accent focus:ring-1 focus:ring-ds-accent transition-colors duration-ds-fast disabled:bg-ds-bg-hover disabled:text-ds-text-muted';

interface Props {
    open: boolean;
    /** null = 新建；否则为编辑目标 */
    editing: ApiKeyPageItem | null;
    onClose: () => void;
    /** 保存成功（新建含明文已确认关闭）后回调刷新列表 */
    onSaved: () => void;
}

export default function KeyFormModal({open, editing, onClose, onSaved}: Props) {
    const [name, setName] = useState('');
    const [qpsLimit, setQpsLimit] = useState(50);
    const [apiOptions, setApiOptions] = useState<DataApiPageItem[]>([]);
    const [selectedApiIds, setSelectedApiIds] = useState<string[]>([]);
    const [pipelineOptions, setPipelineOptions] = useState<CdcPipeline[]>([]);
    const [selectedPipelineIds, setSelectedPipelineIds] = useState<string[]>([]);
    const [loadingDetail, setLoadingDetail] = useState(false);
    const [saving, setSaving] = useState(false);
    const [createdKey, setCreatedKey] = useState<ApiKeyCreateResult | null>(null);

    // 打开时：加载可绑定 API 选项；编辑态预填（经详情端点取当前绑定）
    useEffect(() => {
        if (!open) return;
        setCreatedKey(null);
        setSaving(false);
        setName(editing?.name ?? '');
        setQpsLimit(editing?.qpsLimit ?? 50);
        setSelectedApiIds([]);
        setSelectedPipelineIds([]);
        pageDataApis({page: 1, pageSize: 100})
            .then((res) => setApiOptions(res.data.records ?? []))
            .catch(() => setApiOptions([]));
        pageCdcPipelines({page: 1, pageSize: 100})
            .then((res) => setPipelineOptions(res.records ?? []))
            .catch(() => setPipelineOptions([]));
        if (editing) {
            setLoadingDetail(true);
            getApiKey(editing.id)
                .then((res) => {
                    setName(res.data.name);
                    setQpsLimit(res.data.qpsLimit);
                    setSelectedApiIds(res.data.apiIds ?? []);
                    setSelectedPipelineIds(res.data.pipelineIds ?? []);
                })
                .catch(() => {
                    // 拦截器已提示
                })
                .finally(() => setLoadingDetail(false));
        }
    }, [open, editing]);

    const selectedSet = useMemo(() => new Set(selectedApiIds), [selectedApiIds]);

    const toggleApi = (apiId: string, checked: boolean) => {
        setSelectedApiIds(checked ? [...selectedApiIds, apiId] : selectedApiIds.filter((v) => v !== apiId));
    };
    const togglePipeline = (pipelineId: string, checked: boolean) => {
        setSelectedPipelineIds(checked
            ? [...selectedPipelineIds, pipelineId]
            : selectedPipelineIds.filter((v) => v !== pipelineId));
    };

    const handleSave = async () => {
        if (!name.trim()) {
            notify.warning('请填写 Key 名称');
            return;
        }
        if (!Number.isInteger(qpsLimit) || qpsLimit < 1 || qpsLimit > 10000) {
            notify.warning('限流 QPS 需为 1~10000 的整数');
            return;
        }
        setSaving(true);
        try {
            if (editing) {
                await updateApiKey(editing.id, {name: name.trim(), qpsLimit, apiIds: selectedApiIds, pipelineIds: selectedPipelineIds});
                notify.success(`Key「${name.trim()}」已保存`);
                onSaved();
                onClose();
            } else {
                const res = await createApiKey({name: name.trim(), qpsLimit, apiIds: selectedApiIds, pipelineIds: selectedPipelineIds});
                setCreatedKey(res.data);
                onSaved();
            }
        } catch (err) {
            notify.error(getErrorMessage(err));
        } finally {
            setSaving(false);
        }
    };

    const copyKey = async () => {
        if (!createdKey) return;
        try {
            await navigator.clipboard.writeText(createdKey.apiKey);
            notify.success('Key 已复制到剪贴板');
        } catch {
            notify.warning('复制失败，请检查浏览器剪贴板权限');
        }
    };

    // 创建成功：明文一次性展示视图（禁用遮罩误触关闭，避免明文未保存丢失）
    if (createdKey) {
        return (
            <DsModal
                open={open}
                onClose={onClose}
                title="API Key 创建成功"
                width="w-[520px]"
                closable={false}
                maskClosable={false}
                footer={<DsButton onClick={onClose}>我已保存，关闭</DsButton>}
            >
                <p className="text-ds-small text-ds-text-secondary mb-ds-3">
                    Key「{createdKey.name}」已创建。完整 Key 仅在此展示一次，请立即复制并妥善保管。
                </p>
                <div
                    className="flex items-center gap-ds-2 border border-ds-accent rounded-ds-sm px-ds-3 py-ds-2 bg-ds-accent-light mb-ds-3">
                    <HiOutlineKey size={16} className="text-ds-accent flex-shrink-0"/>
                    <span className="flex-1 text-ds-body text-ds-text-primary font-mono break-all">{createdKey.apiKey}</span>
                    <DsButton variant="secondary" onClick={copyKey}>
                        <HiOutlineClipboardDocument size={14}/>
                        复制
                    </DsButton>
                </div>
                <p className="text-ds-caption text-ds-warning">
                    关闭后将无法再次查看完整 Key；如怀疑泄露，可禁用后重新创建。
                </p>
            </DsModal>
        );
    }

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={editing ? '编辑 API Key' : '新建 API Key'}
            width="w-[560px]"
            bordered
            footer={(
                <>
                    <DsButton variant="ghost" onClick={onClose} disabled={saving}>取消</DsButton>
                    <DsButton onClick={handleSave} loading={saving} disabled={saving || loadingDetail}>
                        {editing ? '保存' : '创建'}
                    </DsButton>
                </>
            )}
        >
            {loadingDetail ? (
                <p className="text-ds-small text-ds-text-muted text-center py-ds-6"><DsSpinner size={14}/> 加载中…</p>
            ) : (
                <div className="flex flex-col gap-ds-4">
                    <div className="grid grid-cols-2 gap-ds-4">
                        <div>
                            <label className="block text-ds-small text-ds-text-secondary mb-1">
                                Key 名称 <span className="text-ds-danger">*</span>
                            </label>
                            <input
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                placeholder="例如：业务-订单组"
                                maxLength={100}
                                className={INPUT_CLASS}
                            />
                        </div>
                        <div>
                            <label className="block text-ds-small text-ds-text-secondary mb-1">
                                限流 QPS <span className="text-ds-danger">*</span>
                            </label>
                            <input
                                type="number"
                                min={1}
                                max={10000}
                                value={qpsLimit}
                                onChange={(e) => setQpsLimit(Number(e.target.value))}
                                className={INPUT_CLASS}
                            />
                            <p className="text-ds-caption text-ds-text-muted mt-1">该 Key 下所有 API 共享此总上限</p>
                        </div>
                    </div>
                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">
                            绑定 API
                            <span className="text-ds-caption text-ds-text-muted ml-ds-2">
                                已选 {selectedApiIds.length} 个；一个 Key 可绑定多个 API
                            </span>
                        </label>
                        <div className="border border-ds-border-subtle rounded-ds-sm max-h-[220px] overflow-y-auto">
                            {apiOptions.length === 0 && (
                                <p className="text-ds-small text-ds-text-muted text-center py-ds-4">
                                    暂无可绑定的 API，可先在「API 管理」新建
                                </p>
                            )}
                            {apiOptions.map((api) => (
                                <label key={api.id}
                                       className="flex items-center gap-ds-2 px-ds-3 py-ds-2 border-t first:border-t-0 border-ds-border-subtle hover:bg-ds-bg-hover cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={selectedSet.has(api.id)}
                                        onChange={(e) => toggleApi(api.id, e.target.checked)}
                                        className="accent-ds-accent"
                                    />
                                    <span className="flex-1 min-w-0 truncate">
                                        <span className="text-ds-small text-ds-text-primary">{api.name}</span>
                                        <span
                                            className="text-ds-caption text-ds-text-muted font-mono ml-ds-2">{api.path}</span>
                                    </span>
                                    <DataApiStatusBadge status={api.status}/>
                                </label>
                            ))}
                        </div>
                    </div>
                    <div>
                        <label className="block text-ds-small text-ds-text-secondary mb-1">
                            绑定 CDC 管道
                            <span className="text-ds-caption text-ds-text-muted ml-ds-2">
                                已选 {selectedPipelineIds.length} 个；绑定后该 Key 即可实时订阅管道变更
                            </span>
                        </label>
                        <div className="border border-ds-border-subtle rounded-ds-sm max-h-[180px] overflow-y-auto">
                            {pipelineOptions.length === 0 && (
                                <p className="text-ds-small text-ds-text-muted text-center py-ds-4">
                                    暂无可绑定的 CDC 管道，可先在「CDC 管道」新建
                                </p>
                            )}
                            {pipelineOptions.map((p) => (
                                <label key={p.id}
                                       className="flex items-center gap-ds-2 px-ds-3 py-ds-2 border-t first:border-t-0 border-ds-border-subtle hover:bg-ds-bg-hover cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={selectedPipelineIds.includes(p.id)}
                                        onChange={(e) => togglePipeline(p.id, e.target.checked)}
                                        className="accent-ds-accent"
                                    />
                                    <span className="flex-1 min-w-0 truncate">
                                        <span className="text-ds-small text-ds-text-primary">{p.name}</span>
                                        <span
                                            className="text-ds-caption text-ds-text-muted font-mono ml-ds-2">{p.sourceDatabase}</span>
                                    </span>
                                    {p.status === 'RUNNING'
                                        ? <DsStatusBadge variant="running" label="运行中"/>
                                        : p.status === 'ERROR'
                                            ? <DsStatusBadge variant="danger" label="异常"/>
                                            : <DsStatusBadge variant="pending" label="已停止"/>}
                                </label>
                            ))}
                        </div>
                    </div>
                </div>
            )}
        </DsModal>
    );
}
