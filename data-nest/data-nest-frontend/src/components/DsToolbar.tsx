import type {ReactNode} from 'react';

/**
 * 列表页工具栏：左侧筛选区（children）+ 右侧操作区（extra，ml-auto 右对齐）。
 * 历史背景：9 个列表页各手写一遍相同的 flex items-center gap-ds-3 flex-wrap
 * 容器和 flex items-center gap-ds-2 ml-auto 操作组。收敛到这里。
 */
interface DsToolbarProps {
    children: ReactNode;
    /** 右侧操作区（查询/重置/新建等按钮） */
    extra?: ReactNode;
}

export default function DsToolbar({children, extra}: DsToolbarProps) {
    return (
        <div className="flex items-center gap-ds-3 flex-wrap">
            {children}
            {extra && (
                <div className="flex items-center gap-ds-2 ml-auto">
                    {extra}
                </div>
            )}
        </div>
    );
}
