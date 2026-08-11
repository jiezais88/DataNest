// Sprint 7 F1：资产表格基础列（数据资产首页 / 分类体系维护页共用）
// 操作列由各页面自行追加。行点击进详情由各页面 onRow 实现。
// Sprint 8 F1：新增「标签」（DC-06）与「热度」（DC-09，近 30 天访问）两列。
import type {ColumnsType} from 'antd/es/table';
import {HiOutlineFire} from 'react-icons/hi2';
import DatabaseTypeIcon from '@/components/DatabaseTypeIcon';
import DsStatusBadge from '@/components/DsStatusBadge';
import QualityScoreBadge from '@/components/QualityScoreBadge';
import {COL} from '@/constants/table';
import {formatDateTime} from '@/utils/format';
import type {AssetSearchItem} from '@/types/asset';

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

/** 标签列：最多展示 2 个，超出折叠 +N（title 悬浮全量）；onTagClick 非空时 chip 可点（按该标签筛选） */
function TagBadges({tags, onTagClick}: { tags?: string[]; onTagClick?: (tag: string) => void }) {
    if (!tags || tags.length === 0) return <span className="text-ds-small text-ds-text-muted">—</span>;
    const shown = tags.slice(0, 2);
    const rest = tags.length - shown.length;
    return (
        <span className="flex items-center gap-ds-1 flex-wrap" title={tags.join('、')}>
            {shown.map(name => (
                onTagClick ? (
                    <button key={name} type="button" title={`按标签「${name}」筛选`}
                            className="inline-flex items-center px-2 py-0.5 rounded-full text-ds-badge bg-ds-accent-light text-ds-accent whitespace-nowrap hover:underline"
                            // 行点击是进详情，chip 点击是筛选，阻止冒泡
                            onClick={(e) => {
                                e.stopPropagation();
                                onTagClick(name);
                            }}>
                        {name}
                    </button>
                ) : (
                    <span key={name}
                          className="inline-flex items-center px-2 py-0.5 rounded-full text-ds-badge bg-ds-accent-light text-ds-accent whitespace-nowrap">
                        {name}
                    </span>
                )
            ))}
            {rest > 0 && <span className="text-ds-tiny text-ds-text-muted">+{rest}</span>}
        </span>
    );
}

/** 热度列：火焰图标 + 近 30 天访问数（后端全场景回填，Long 序列化为 string，无访问为 "0"） */
function HotValue({value}: { value?: string }) {
    if (value == null) return <span className="text-ds-small text-ds-text-muted">—</span>;
    return (
        <span className="inline-flex items-center gap-ds-1 text-ds-small text-ds-warning">
            <HiOutlineFire size={14}/>
            {value}
        </span>
    );
}

export function buildAssetColumns(openDetail: (tableId: string) => void,
                                  onTagClick?: (tag: string) => void): ColumnsType<AssetSearchItem> {
    return [
        {
            title: '表名',
            dataIndex: 'tableName',
            width: 180,
            ellipsis: true,
            // 列多横向滚动时表名钉在左侧，保持行上下文（操作列钉右侧）
            fixed: 'left' as const,
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
            width: 180,
            ellipsis: true,
            render: (v?: string) => (
                <span title={v || ''}
                      className="text-ds-small text-ds-text-secondary">{v || '—'}</span>
            ),
        },
        {
            title: '数据源',
            dataIndex: 'datasourceName',
            width: 150,
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
            width: 140,
            render: (_, r) => <ClassificationBadges domain={r.dataDomain} topic={r.dataTopic}/>,
        },
        {
            title: '标签',
            dataIndex: 'tags',
            width: 130,
            render: (v?: string[]) => <TagBadges tags={v} onTagClick={onTagClick}/>,
        },
        {
            title: '负责人',
            dataIndex: 'ownerName',
            width: 110,
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
            width: 120,
            render: (v?: number, r?: AssetSearchItem) => (
                <QualityScoreBadge table score={v ?? null} healthLevel={r?.healthLevel}/>
            ),
        },
        {
            title: '热度',
            dataIndex: 'viewCount',
            width: 90,
            render: (v?: string) => <HotValue value={v}/>,
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
