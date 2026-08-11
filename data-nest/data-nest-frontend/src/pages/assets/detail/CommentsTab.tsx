// Sprint 8 F1：资产详情评论页签（DC-08）。
// 全角色可评论（按表维度，不嵌套）；作者可删自己的评论，治理员/超管可删任意（后端 4023 兜底）。
// 用户名由后端批量回填：用户已注销显示「已注销」，用户服务不可用降级「—」（B4）。
import {useState} from 'react';
import {HiOutlineTrash} from 'react-icons/hi2';
import {addAssetComment, deleteAssetComment, listAssetComments} from '@/api/asset';
import ConfirmDialog from '@/components/ConfirmDialog';
import DsButton from '@/components/DsButton';
import DsTableEmpty from '@/components/DsTableEmpty';
import Pagination from '@/components/Pagination';
import {GOVERNANCE_WRITE_ROLES} from '@/constants/roles';
import usePagedList from '@/hooks/usePagedList';
import {useHasRole} from '@/hooks/useHasRole';
import {useAuthStore} from '@/store/useAuthStore';
import {formatDateTime} from '@/utils/format';
import {notify} from '@/utils/notify';
import type {AssetComment} from '@/types/asset';

const MAX_COMMENT_LENGTH = 2000;

interface CommentsTabProps {
    tableId: string;
    /** 评论数变化（发表/删除）后通知父级刷新协作聚合（页签计数） */
    onCountChange: () => void;
}

export default function CommentsTab({tableId, onCountChange}: CommentsTabProps) {
    const {userInfo} = useAuthStore();
    const canModerate = useHasRole(...GOVERNANCE_WRITE_ROLES);

    const [content, setContent] = useState('');
    const [publishing, setPublishing] = useState(false);
    const [deleting, setDeleting] = useState<string | null>(null);
    const [deleteLoading, setDeleteLoading] = useState(false);

    const {list, total, page, pageSize, loading, setPage, setPageSize, reload} = usePagedList<Record<string, never>, AssetComment>({
        fetcher: ({page: p, pageSize: ps}) => listAssetComments(tableId, p, ps)
            .then(r => ({list: r?.records ?? [], total: Number(r?.total ?? 0)})),
        initialQuery: {},
    });

    const canDelete = (commentUserId: string) =>
        canModerate || (userInfo?.userId != null && String(commentUserId) === String(userInfo.userId));

    const handlePublish = async () => {
        const trimmed = content.trim();
        if (!trimmed || publishing) return;
        setPublishing(true);
        try {
            await addAssetComment(tableId, trimmed);
            setContent('');
            notify.success('评论已发表');
            // 列表按时间倒序，新评论在第 1 页；翻页状态下发表要回第 1 页才能看到
            if (page !== 1) setPage(1);
            else reload();
            onCountChange();
        } catch {
            // 4024（内容校验失败）由拦截器统一提示
        } finally {
            setPublishing(false);
        }
    };

    const handleDelete = async () => {
        if (!deleting) return;
        setDeleteLoading(true);
        try {
            await deleteAssetComment(deleting);
            notify.success('评论已删除');
            // 删掉当前页最后一条且不在第 1 页时回退一页，避免停留在空页
            if (list.length === 1 && page > 1) setPage(page - 1);
            else reload();
            onCountChange();
        } catch {
            // 4022/4023 由拦截器统一提示
        } finally {
            setDeleteLoading(false);
            setDeleting(null);
        }
    };

    return (
        <div>
            {/* 发表区 */}
            <div className="flex gap-ds-3 mb-ds-4">
                <div
                    className="w-8 h-8 rounded-full bg-ds-accent text-white flex items-center justify-center text-ds-small font-bold flex-shrink-0">
                    {(userInfo?.username || '?').charAt(0).toUpperCase()}
                </div>
                <div className="flex-1 min-w-0">
                    <textarea
                        value={content}
                        onChange={(e) => setContent(e.target.value)}
                        placeholder="写下你对这张表的看法，帮助同事更好地使用…"
                        maxLength={MAX_COMMENT_LENGTH}
                        rows={3}
                        className="w-full px-ds-3 py-ds-2 text-ds-small border border-ds-border-subtle rounded-ds-sm outline-none focus:border-ds-accent bg-ds-bg-surface text-ds-text-primary resize-y"
                    />
                    <div className="flex items-center justify-between mt-ds-2">
                        <span className="text-ds-tiny text-ds-text-muted">
                            {content.length}/{MAX_COMMENT_LENGTH}
                        </span>
                        <DsButton onClick={handlePublish} disabled={!content.trim() || publishing}
                                  loading={publishing}>
                            发表
                        </DsButton>
                    </div>
                </div>
            </div>

            {/* 评论列表 */}
            {loading ? (
                <div className="py-ds-8 text-center text-ds-small text-ds-text-muted">加载中...</div>
            ) : list.length === 0 ? (
                <DsTableEmpty description="暂无评论，来发表第一条看法吧"/>
            ) : (
                <div className="divide-y divide-ds-border-subtle">
                    {list.map(comment => {
                        const deleted = comment.username === '已注销';
                        return (
                            <div key={comment.commentId} className="flex gap-ds-3 py-ds-3">
                                <div
                                    className={`w-8 h-8 rounded-full flex items-center justify-center text-ds-small font-bold flex-shrink-0 ${
                                        deleted ? 'bg-ds-bg-hover text-ds-text-muted' : 'bg-ds-accent-light text-ds-accent'
                                    }`}>
                                    {(comment.username || '—').charAt(0)}
                                </div>
                                <div className="flex-1 min-w-0">
                                    <div className="flex items-center gap-ds-2">
                                        <span
                                            className={`text-ds-small font-semibold ${deleted ? 'text-ds-text-muted' : 'text-ds-text-primary'}`}>
                                            {comment.username || '—'}
                                        </span>
                                        <span
                                            className="text-ds-tiny text-ds-text-muted">{formatDateTime(comment.createdAt)}</span>
                                    </div>
                                    <p className="text-ds-small text-ds-text-secondary mt-ds-1 whitespace-pre-wrap break-words">
                                        {comment.content}
                                    </p>
                                </div>
                                {canDelete(comment.userId) && (
                                    <button
                                        type="button"
                                        onClick={() => setDeleting(comment.commentId)}
                                        className="inline-flex items-center gap-ds-1 text-ds-tiny text-ds-text-muted hover:text-ds-danger flex-shrink-0 self-start"
                                    >
                                        <HiOutlineTrash size={12}/>
                                        删除
                                    </button>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}

            {total > 0 && (
                <Pagination
                    page={page}
                    pageSize={pageSize}
                    total={total}
                    onChange={(p, s) => {
                        setPage(p);
                        if (s !== pageSize) setPageSize(s);
                    }}
                />
            )}

            <ConfirmDialog
                open={!!deleting}
                title="删除评论"
                message="确认删除这条评论？删除后不再展示。"
                confirmLabel="删除"
                danger
                loading={deleteLoading}
                onConfirm={handleDelete}
                onCancel={() => setDeleting(null)}
            />
        </div>
    );
}
