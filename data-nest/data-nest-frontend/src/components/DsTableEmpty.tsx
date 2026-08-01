import {Empty} from 'antd';
import type {ReactNode} from 'react';

/**
 * 表格空态统一写法（antd Table 的 locale.emptyText）。历史背景：13 处各写一遍
 * <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="..."/>，带操作按钮的
 * 空态按钮还不居中。收敛到这里；页面级空态（非表格）仍用 EmptyState 组件。
 */
interface DsTableEmptyProps {
    description: ReactNode;
    /** 空态操作按钮（如「新建」），渲染在描述下方并居中 */
    action?: ReactNode;
}

export default function DsTableEmpty({description, action}: DsTableEmptyProps) {
    return (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={description}>
            {action && (
                <div className="flex justify-center">
                    {action}
                </div>
            )}
        </Empty>
    );
}
