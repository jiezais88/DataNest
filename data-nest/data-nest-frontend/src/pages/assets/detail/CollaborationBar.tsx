// Sprint 8 F1：资产详情协作条（DC-06 标签区 + DC-07 收藏/关注按钮）。
// 标签全角色可打/删（平台级标签，输入即建、同名复用）；收藏/关注为个人维度，后端 uk 幂等。
// 点击标签跳转资产目录按该标签筛选（PRD §6.2）。
import {useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Tooltip} from 'antd';
import {HiOutlineBell, HiOutlinePlus, HiOutlineStar, HiOutlineTag, HiOutlineXMark, HiBell, HiStar} from 'react-icons/hi2';
import {addTableTag, favoriteAsset, followAsset, removeTableTag, unfavoriteAsset, unfollowAsset} from '@/api/asset';
import {notify} from '@/utils/notify';
import type {AssetCollaboration} from '@/types/asset';

interface CollaborationBarProps {
    tableId: string;
    collaboration: AssetCollaboration | null;
    /** 协作状态变化（打/删标签、收藏/关注切换）后回传最新聚合状态 */
    onChange: (next: AssetCollaboration) => void;
}

export default function CollaborationBar({tableId, collaboration, onChange}: CollaborationBarProps) {
    const navigate = useNavigate();
    const [editing, setEditing] = useState(false);
    const [tagInput, setTagInput] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [togglingFav, setTogglingFav] = useState(false);
    const [togglingFollow, setTogglingFollow] = useState(false);

    const tags = collaboration?.tags ?? [];
    const favorited = collaboration?.favorited ?? false;
    const followed = collaboration?.followed ?? false;
    // 聚合状态未返回（加载中/接口失败）时禁点收藏/关注，避免幂等写后 toast 误报与状态覆盖
    const collaborationReady = collaboration != null;

    const submitTag = async () => {
        const name = tagInput.trim();
        if (!name || submitting) return;
        setSubmitting(true);
        try {
            const nextTags = await addTableTag(tableId, name);
            onChange({...(collaboration ?? {}), tags: nextTags ?? []});
            setTagInput('');
            setEditing(false);
        } catch {
            // 4024（标签名校验失败）由拦截器统一提示，输入框保留便于修正
        } finally {
            setSubmitting(false);
        }
    };

    const handleRemoveTag = async (tagId: string, tagName: string) => {
        try {
            const nextTags = await removeTableTag(tableId, tagId);
            onChange({...(collaboration ?? {}), tags: nextTags ?? []});
            notify.success(`已移除标签「${tagName}」`);
        } catch {
            // 拦截器已提示
        }
    };

    const toggleFavorite = async () => {
        if (togglingFav) return;
        setTogglingFav(true);
        try {
            if (favorited) await unfavoriteAsset(tableId);
            else await favoriteAsset(tableId);
            onChange({...(collaboration ?? {}), favorited: !favorited});
            notify.success(favorited ? '已取消收藏' : '已收藏');
        } catch {
            // 拦截器已提示
        } finally {
            setTogglingFav(false);
        }
    };

    const toggleFollow = async () => {
        if (togglingFollow) return;
        setTogglingFollow(true);
        try {
            if (followed) await unfollowAsset(tableId);
            else await followAsset(tableId);
            onChange({...(collaboration ?? {}), followed: !followed});
            notify.success(followed ? '已取消关注' : '已关注，表变更将在「我的关注」展示');
        } catch {
            // 拦截器已提示
        } finally {
            setTogglingFollow(false);
        }
    };

    return (
        <div
            className="flex items-center justify-between gap-ds-4 flex-wrap bg-ds-bg-surface border border-ds-border-subtle rounded-ds-md px-ds-4 py-ds-3 mb-ds-4 flex-shrink-0">
            {/* 标签区 */}
            <div className="flex items-center gap-ds-2 flex-wrap min-w-0">
                <HiOutlineTag size={14} className="text-ds-text-muted flex-shrink-0"/>
                {tags.map(tag => (
                    <span key={tag.tagId}
                          className="group inline-flex items-center gap-ds-1 px-2.5 py-1 rounded-full text-ds-badge bg-ds-accent-light text-ds-accent">
                        <button
                            type="button"
                            className="hover:underline"
                            title={`按标签「${tag.tagName}」筛选资产`}
                            onClick={() => navigate(`/asset-catalog?tag=${encodeURIComponent(tag.tagName)}`)}
                        >
                            {tag.tagName}
                        </button>
                        <button
                            type="button"
                            aria-label={`移除标签 ${tag.tagName}`}
                            title="移除标签"
                            className="opacity-0 group-hover:opacity-100 transition-opacity hover:text-ds-danger"
                            onClick={() => handleRemoveTag(tag.tagId, tag.tagName)}
                        >
                            <HiOutlineXMark size={12}/>
                        </button>
                    </span>
                ))}
                {tags.length === 0 && !editing && (
                    <span className="text-ds-tiny text-ds-text-muted">暂无标签</span>
                )}
                {editing ? (
                    <input
                        autoFocus
                        value={tagInput}
                        onChange={(e) => setTagInput(e.target.value)}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter') submitTag();
                            if (e.key === 'Escape') {
                                setTagInput('');
                                setEditing(false);
                            }
                        }}
                        onBlur={() => {
                            // 有内容失焦视同确认；空内容失焦取消
                            if (tagInput.trim()) submitTag();
                            else setEditing(false);
                        }}
                        disabled={submitting}
                        placeholder="输入标签名，回车创建/复用"
                        maxLength={100}
                        className="w-48 px-2 py-1 text-ds-small border border-ds-accent rounded-ds-sm outline-none bg-ds-bg-surface text-ds-text-primary"
                    />
                ) : (
                    <Tooltip title="输入即建，同名自动复用">
                        <button
                            type="button"
                            onClick={() => setEditing(true)}
                            className="inline-flex items-center gap-ds-1 px-2.5 py-1 rounded-full text-ds-badge border border-dashed border-ds-border-strong text-ds-text-muted hover:text-ds-accent hover:border-ds-accent transition-colors"
                        >
                            <HiOutlinePlus size={12}/>
                            添加标签
                        </button>
                    </Tooltip>
                )}
            </div>

            {/* 收藏 / 关注 */}
            <div className="flex items-center gap-ds-2 flex-shrink-0">
                <button
                    type="button"
                    onClick={toggleFavorite}
                    disabled={togglingFav || !collaborationReady}
                    className={`inline-flex items-center gap-ds-1 px-ds-3 py-ds-2 text-ds-small font-semibold rounded-ds-sm border transition-colors disabled:opacity-60 ${
                        favorited
                            ? 'bg-ds-warning-light border-transparent text-ds-warning'
                            : 'bg-white border-ds-border-subtle text-ds-text-secondary hover:border-ds-border-strong'
                    }`}
                >
                    {favorited ? <HiStar size={14}/> : <HiOutlineStar size={14}/>}
                    {favorited ? '已收藏' : '收藏'}
                </button>
                <button
                    type="button"
                    onClick={toggleFollow}
                    disabled={togglingFollow || !collaborationReady}
                    className={`inline-flex items-center gap-ds-1 px-ds-3 py-ds-2 text-ds-small font-semibold rounded-ds-sm border transition-colors disabled:opacity-60 ${
                        followed
                            ? 'bg-ds-accent-light border-transparent text-ds-accent'
                            : 'bg-white border-ds-border-subtle text-ds-text-secondary hover:border-ds-border-strong'
                    }`}
                >
                    {followed ? <HiBell size={14}/> : <HiOutlineBell size={14}/>}
                    {followed ? '已关注' : '关注'}
                </button>
            </div>
        </div>
    );
}
