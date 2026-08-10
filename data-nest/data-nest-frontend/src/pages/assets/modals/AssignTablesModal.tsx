// Sprint 7 F1：批量分配表到分类弹窗（分类体系维护页用）
// 候选表：默认列「未分类」表；输入关键词后走 /assets/search 多维搜索。
// 后端无批量接口，提交时循环调单表 PUT，部分失败汇总提示。
import {useCallback, useEffect, useMemo, useState} from 'react';
import {Table} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {assignTablesClassificationBatch, browseAssets, searchAssets} from '@/api/asset';
import DatabaseTypeIcon from '@/components/DatabaseTypeIcon';
import DsButton from '@/components/DsButton';
import DsModal from '@/components/DsModal';
import DsStatusBadge from '@/components/DsStatusBadge';
import DsTableEmpty from '@/components/DsTableEmpty';
import SearchInput from '@/components/SearchInput';
import {COL} from '@/constants/table';
import {notify} from '@/utils/notify';
import type {AssetSearchItem} from '@/types/asset';

interface AssignTablesModalProps {
    open: boolean;
    /** 目标分类（domain 必有；topic 可选 = 分配到域） */
    domain: string;
    topic?: string;
    onClose: () => void;
    onSaved: () => void;
}

const CANDIDATE_PAGE_SIZE = 50;

export default function AssignTablesModal({
                                              open,
                                              domain,
                                              topic,
                                              onClose,
                                              onSaved,
                                          }: AssignTablesModalProps) {
    const [keywordInput, setKeywordInput] = useState('');
    const [keyword, setKeyword] = useState('');
    const [list, setList] = useState<AssetSearchItem[]>([]);
    const [loading, setLoading] = useState(false);
    const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
    const [submitting, setSubmitting] = useState(false);

    const loadCandidates = useCallback(() => {
        setLoading(true);
        const req = keyword.trim()
            ? searchAssets(keyword.trim())
            : browseAssets({uncategorized: true, page: 1, pageSize: CANDIDATE_PAGE_SIZE}).then(r => r?.records ?? []);
        req
            .then(items => setList(items ?? []))
            .catch(() => setList([]))
            .finally(() => setLoading(false));
    }, [keyword]);

    useEffect(() => {
        if (!open) return;
        setKeywordInput('');
        setKeyword('');
        setSelectedKeys([]);
        loadCandidates();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open]);

    useEffect(() => {
        if (open) loadCandidates();
    }, [keyword, open, loadCandidates]);

    const targetLabel = topic ? `${domain} / ${topic}` : domain;

    const handleSubmit = async () => {
        if (selectedKeys.length === 0) return;
        setSubmitting(true);
        try {
            // 后端批量接口：一次校验 + 一条 UPDATE ... IN（替代循环单表调用）
            const updated = await assignTablesClassificationBatch(selectedKeys, {
                dataDomain: domain,
                dataTopic: topic ?? null,
            });
            notify.success(`已将 ${updated ?? selectedKeys.length} 张表分配到「${targetLabel}」`);
            onSaved();
            onClose();
        } catch {
            // 错误提示由拦截器统一弹出
        } finally {
            setSubmitting(false);
        }
    };

    const columns = useMemo<ColumnsType<AssetSearchItem>>(() => [
        {
            title: '表名',
            dataIndex: 'tableName',
            width: COL.NAME_COMPACT,
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-accent font-mono" title={v}>{v || '—'}</span>
            ),
        },
        {
            title: '注释',
            dataIndex: 'tableComment',
            ellipsis: true,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary" title={v}>{v || '—'}</span>
            ),
        },
        {
            title: '数据源 · 库',
            key: 'datasource',
            width: 180,
            ellipsis: true,
            render: (_, r) => (
                r.datasourceType ? (
                    <span className="inline-flex items-center gap-ds-1 text-ds-small text-ds-text-secondary">
                        <DatabaseTypeIcon type={r.datasourceType} size={14} showLabel={false}/>
                        <span className="truncate">{r.datasourceName || r.datasourceType}{r.databaseName ? ` · ${r.databaseName}` : ''}</span>
                    </span>
                ) : (
                    <span className="text-ds-small text-ds-text-muted">—</span>
                )
            ),
        },
        {
            title: '当前分类',
            key: 'classification',
            width: 140,
            render: (_, r) => (
                r.dataDomain ? (
                    <span className="flex items-center gap-ds-1 flex-wrap">
                        <DsStatusBadge variant="accent" label={r.dataDomain}/>
                        {r.dataTopic && <DsStatusBadge variant="disabled" label={r.dataTopic}/>}
                    </span>
                ) : (
                    <DsStatusBadge variant="disabled" label="未分类"/>
                )
            ),
        },
    ], []);

    return (
        <DsModal
            open={open}
            onClose={onClose}
            title={`分配表到「${targetLabel}」`}
            width="w-[680px]"
            bordered
            footer={
                <div className="flex items-center justify-between w-full">
                    <span className="text-ds-small text-ds-text-muted">已选 {selectedKeys.length} 张表</span>
                    <div className="flex items-center gap-ds-2">
                        <DsButton variant="secondary" onClick={onClose} disabled={submitting}>取消</DsButton>
                        <DsButton
                            variant="primary"
                            onClick={handleSubmit}
                            disabled={submitting || selectedKeys.length === 0}
                        >
                            {submitting ? '分配中...' : `批量分配（${selectedKeys.length}）`}
                        </DsButton>
                    </div>
                </div>
            }
        >
            <div className="space-y-ds-3">
                <SearchInput
                    value={keywordInput}
                    onChange={(e) => setKeywordInput(e.target.value)}
                    onEnter={() => setKeyword(keywordInput.trim())}
                    placeholder="搜索表名 / 注释 / 负责人，回车查询；默认列出未分类表"
                    aria-label="搜索候选表"
                    className="max-w-none"
                />
                <Table
                    rowKey={(r) => r.tableId}
                    columns={columns}
                    dataSource={list}
                    loading={loading}
                    pagination={false}
                    scroll={{y: 320}}
                    rowSelection={{
                        selectedRowKeys: selectedKeys,
                        onChange: (keys) => setSelectedKeys(keys.map(String)),
                    }}
                    className="prototype-table prototype-table-flush"
                    locale={{
                        emptyText: (
                            <DsTableEmpty
                                description={keyword ? '未找到匹配的表' : '暂无未分类的表'}
                            />
                        ),
                    }}
                />
            </div>
        </DsModal>
    );
}
