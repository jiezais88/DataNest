// Sprint 7 F1：资产表格基础列（数据资产首页 / 分类体系维护页共用）
// 操作列由各页面自行追加。行点击进详情由各页面 onRow 实现。
import type {ColumnsType} from 'antd/es/table';
import DatabaseTypeIcon from '../../components/DatabaseTypeIcon';
import DsStatusBadge from '../../components/DsStatusBadge';
import QualityScoreBadge from '../../components/QualityScoreBadge';
import {COL} from '../../constants/table';
import {formatDateTime} from '../../utils/format';
import type {AssetSearchItem} from '../../types/asset';

/** 数据域/主题徽章：域 = accent，主题 = 灰（neutral），无则「—」 */
function ClassificationBadges({domain, topic}: { domain?: string; topic?: string }) {
    if (!domain) return <span className="text-ds-small text-ds-text-muted">—</span>;
    return (
        <span className="flex items-center gap-ds-1 flex-wrap">
            <DsStatusBadge variant="accent" label={domain}/>
            {topic && <DsStatusBadge variant="disabled" label={topic}/>}
        </span>
    );
}

export function buildAssetColumns(openDetail: (tableId: string) => void): ColumnsType<AssetSearchItem> {
    return [
        {
            title: '表名',
            dataIndex: 'tableName',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string, r?: AssetSearchItem) => (
                <button
                    type="button"
                    onClick={(e) => {
                        e.stopPropagation();
                        if (r?.tableId) openDetail(r.tableId);
                    }}
                    title={r?.databaseName ? `${r.databaseName}.${v}` : v}
                    className="text-ds-small text-ds-accent hover:underline text-left font-medium"
                >
                    {v || '—'}
                </button>
            ),
        },
        {
            title: '注释',
            dataIndex: 'tableComment',
            width: COL.NAME,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || ''}
                      className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '数据源',
            dataIndex: 'datasourceName',
            width: 180,
            ellipsis: true,
            render: (v?: string, r?: AssetSearchItem) => (
                r?.datasourceType ? (
                    <span className="inline-flex items-center gap-ds-1 text-ds-small text-ds-text-secondary"
                          title={r?.databaseName ? `${v || ''} · ${r.databaseName}` : v}>
                        <DatabaseTypeIcon type={r.datasourceType} size={16} showLabel={false}/>
                        <span className="truncate">{v || r.datasourceType}</span>
                    </span>
                ) : (
                    <span className="text-ds-small text-ds-text-muted">—</span>
                )
            ),
        },
        {
            title: '数据域 / 主题',
            key: 'classification',
            width: 160,
            render: (_, r) => <ClassificationBadges domain={r.dataDomain} topic={r.dataTopic}/>,
        },
        {
            title: '负责人',
            dataIndex: 'ownerName',
            width: COL.USERNAME,
            ellipsis: true,
            render: (v?: string) => (
                <span className={`text-ds-small ${v ? 'text-ds-text-secondary' : 'text-ds-text-muted'}`}>
                    {v || '—'}
                </span>
            ),
        },
        {
            title: '质量评分',
            dataIndex: 'qualityScore',
            width: 140,
            render: (v?: number, r?: AssetSearchItem) => (
                <QualityScoreBadge table score={v ?? null} healthLevel={r?.healthLevel}/>
            ),
        },
        {
            title: '最近更新',
            dataIndex: 'updatedAt',
            width: COL.DATETIME_COMPACT,
            render: (v?: string) => (
                <span className="text-ds-small text-ds-text-secondary whitespace-nowrap">
                    {formatDateTime(v)}
                </span>
            ),
        },
    ];
}
