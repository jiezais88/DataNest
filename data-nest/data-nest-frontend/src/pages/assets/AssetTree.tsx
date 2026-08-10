// Sprint 7 F1：资产分类树（数据资产首页用）
// 结构：全部资产（置顶）→ 数据域（可展开）→ 主题（缩进叶子）→ 未分类（垫底）。
// 节点带表数计数徽章（后端 /assets/classifications 返回）；editable 时 hover 显示改名/删除。
// UX（2026-08-10 方案 A+B）：节点名称双行布局（主名 + 级别说明），长名 truncate 且 hover Tooltip 显示完整名。
import {useState} from 'react';
import {Tooltip} from 'antd';
import {
    HiChevronRight,
    HiOutlineFolderOpen,
    HiOutlinePencilSquare,
    HiOutlineSparkles,
    HiOutlineTag,
    HiOutlineTrash,
} from 'react-icons/hi2';
import DsIconButton from '@/components/DsIconButton';
import type {AssetClassification} from '@/types/asset';
import type {AssetBrowseQuery} from '@/types/asset';

/** 树选中项：全部 / 未分类 / 数据域 / 主题（domain/topic 为分类名，对齐 browse 接口） */
export interface AssetTreeSelection {
    type: 'all' | 'uncategorized' | 'domain' | 'topic';
    domain?: string;
    topic?: string;
}

export const ALL_SELECTION: AssetTreeSelection = {type: 'all'};

/** 选中项 → 树高亮 key */
export function selectionKey(sel: AssetTreeSelection): string {
    switch (sel.type) {
        case 'all':
            return 'all';
        case 'uncategorized':
            return 'uncategorized';
        case 'domain':
            return `domain:${sel.domain}`;
        case 'topic':
            return `topic:${sel.domain}/${sel.topic}`;
    }
}

/** 选中项 → /assets/browse 查询参数（全部资产 = 不带过滤条件） */
export function selectionToQuery(sel: AssetTreeSelection): AssetBrowseQuery {
    switch (sel.type) {
        case 'uncategorized':
            return {uncategorized: true};
        case 'domain':
            return {domain: sel.domain};
        case 'topic':
            return {domain: sel.domain, topic: sel.topic};
        default:
            return {};
    }
}

interface AssetTreeProps {
    tree: AssetClassification[];
    selectedKey: string;
    onSelect: (sel: AssetTreeSelection) => void;
    /** 「全部资产」计数 */
    allCount?: number;
    /** 「未分类」计数 */
    uncategorizedCount?: number;
    /** 是否展示「未分类」兜底节点，默认 true */
    showUncategorized?: boolean;
    /** 编辑模式：节点 hover 显示改名/删除按钮（治理员） */
    editable?: boolean;
    onEdit?: (node: AssetClassification, parent?: AssetClassification) => void;
    onDelete?: (node: AssetClassification) => void;
}

export default function AssetTree({
                                      tree,
                                      selectedKey,
                                      onSelect,
                                      allCount,
                                      uncategorizedCount,
                                      showUncategorized = true,
                                      editable = false,
                                      onEdit,
                                      onDelete,
                                  }: AssetTreeProps) {
    // 默认全部展开，点击 chevron 收起（避免 tree 异步到达后的初始化时机问题）
    const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

    const toggleCollapse = (id: string) => {
        setCollapsed(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return next;
        });
    };

    const rowClass = (active: boolean) =>
        `group flex items-center gap-ds-2 w-full px-ds-3 py-[5px] rounded-ds-sm text-ds-small cursor-pointer relative transition-colors ${
            active ? 'bg-ds-accent-light text-ds-accent font-semibold' : 'text-ds-text-secondary hover:bg-ds-bg-hover'
        }`;

    const activeBar = (active: boolean) =>
        active ? (
            <span className="absolute left-0 top-1/2 -translate-y-1/2 w-[3px] h-4 bg-ds-accent rounded-full"/>
        ) : null;

    /** 节点名称双行块（方案 A+B）：主名 truncate + hover Tooltip 完整名，副行级别说明 */
    const nameBlock = (name: string, sub: string) => (
        <span className="flex-1 min-w-0 flex flex-col justify-center gap-[1px]">
            <Tooltip title={name} mouseEnterDelay={0.3}>
                <span className="block truncate text-left leading-tight">{name}</span>
            </Tooltip>
            <span className="block truncate text-left leading-tight text-ds-tiny text-ds-text-muted">{sub}</span>
        </span>
    );

    /** 计数徽章：active 时 accent 实心白字，否则灰底（对齐原型 tree-count） */
    const countBadge = (count: number | undefined, active: boolean) =>
        count === undefined ? null : (
            <span className={`min-w-[20px] text-center text-[11px] px-1.5 py-0.5 rounded-full ${
                active ? 'bg-ds-accent text-white' : 'bg-ds-bg-hover text-ds-text-muted'
            }`}>
                {count}
            </span>
        );

    const editActions = (node: AssetClassification, parent?: AssetClassification) =>
        editable ? (
            <span className="flex items-center opacity-0 group-hover:opacity-100 transition-opacity">
                <DsIconButton
                    tone="accent"
                    aria-label={`编辑 ${node.name}`}
                    onClick={(e) => {
                        e.stopPropagation();
                        onEdit?.(node, parent);
                    }}
                >
                    <HiOutlinePencilSquare size={14}/>
                </DsIconButton>
                <DsIconButton
                    tone="danger"
                    aria-label={`删除 ${node.name}`}
                    onClick={(e) => {
                        e.stopPropagation();
                        onDelete?.(node);
                    }}
                >
                    <HiOutlineTrash size={14}/>
                </DsIconButton>
            </span>
        ) : null;

    return (
        <div className="p-ds-3">
            <div className="flex items-center gap-ds-2 px-ds-3 pb-ds-2 text-ds-small font-semibold text-ds-text-primary">
                <HiOutlineFolderOpen size={16} className="text-ds-accent"/>
                数据域 / 主题
            </div>

            {/* 全部资产 */}
            <button type="button" className={rowClass(selectedKey === 'all')}
                    onClick={() => onSelect(ALL_SELECTION)}>
                {activeBar(selectedKey === 'all')}
                <HiOutlineSparkles size={15} className="flex-shrink-0"/>
                {nameBlock('全部资产', '全部数据表')}
                {countBadge(allCount, selectedKey === 'all')}
            </button>

            {/* 数据域 → 主题 */}
            {tree.map(domain => {
                const domainKey = `domain:${domain.name}`;
                const isCollapsed = collapsed.has(domain.id);
                const topics = domain.children ?? [];
                return (
                    <div key={domain.id}>
                        <div className={rowClass(selectedKey === domainKey)}
                             onClick={() => onSelect({type: 'domain', domain: domain.name})}>
                            {activeBar(selectedKey === domainKey)}
                            <HiChevronRight
                                size={13}
                                className={`flex-shrink-0 text-ds-text-muted transition-transform ${isCollapsed ? '' : 'rotate-90'}`}
                                onClick={(e) => {
                                    e.stopPropagation();
                                    toggleCollapse(domain.id);
                                }}
                            />
                            <HiOutlineFolderOpen size={15} className="flex-shrink-0"/>
                            {nameBlock(domain.name, '数据域')}
                            {editActions(domain)}
                            {countBadge(domain.tableCount, selectedKey === domainKey)}
                        </div>
                        {!isCollapsed && topics.length > 0 && (
                            <div className="ml-5 border-l border-ds-border-subtle">
                                {topics.map(topic => {
                                    const topicKey = `topic:${domain.name}/${topic.name}`;
                                    return (
                                        <div key={topic.id}
                                             className={rowClass(selectedKey === topicKey)}
                                             onClick={() => onSelect({
                                                 type: 'topic',
                                                 domain: domain.name,
                                                 topic: topic.name,
                                             })}>
                                            {activeBar(selectedKey === topicKey)}
                                            <HiOutlineTag size={14} className="flex-shrink-0"/>
                                            {nameBlock(topic.name, '主题')}
                                            {editActions(topic, domain)}
                                            {countBadge(topic.tableCount, selectedKey === topicKey)}
                                        </div>
                                    );
                                })}
                            </div>
                        )}
                    </div>
                );
            })}

            {/* 未分类 */}
            {showUncategorized && (
                <button type="button" className={rowClass(selectedKey === 'uncategorized')}
                        onClick={() => onSelect({type: 'uncategorized'})}>
                    {activeBar(selectedKey === 'uncategorized')}
                    <HiOutlineTag size={15} className="flex-shrink-0"/>
                    {nameBlock('未分类', '未归入分类')}
                    {countBadge(uncategorizedCount, selectedKey === 'uncategorized')}
                </button>
            )}

            {tree.length === 0 && (
                <p className="px-ds-3 py-ds-2 text-ds-tiny text-ds-text-muted">
                    暂无分类{editable ? '，点击右上「新增数据域」创建' : ''}
                </p>
            )}
        </div>
    );
}
