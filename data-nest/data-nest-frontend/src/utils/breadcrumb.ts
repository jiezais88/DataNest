// Breadcrumb map + resolver. Shared by Layout (for document.title) and
// Breadcrumb component (for rendering the nav).

export type BreadcrumbEntry = { group?: string; label: string };

export type BreadcrumbSegment = { label: string; path: string };

/**
 * Static breadcrumb map. Each entry is keyed by the exact pathname.
 * `group` represents the parent group label (e.g. "数据工程") and is
 * prepended when rendering so that deep pages read as
 * "数据工程 / DAG 编排 / 编辑".
 */
export const breadcrumbMap: Record<string, BreadcrumbEntry> = {
    '/': {label: '首页'},
    '/system/users': {group: '系统管理', label: '用户管理'},
    '/engineering/datasources': {group: '数据工程', label: '数据源管理'},
    '/engineering/sync-jobs': {group: '数据工程', label: '批量数据同步任务'},
    '/engineering/sync-job-history': {group: '数据工程', label: '同步任务历史'},
    '/engineering/dags': {group: '数据开发', label: '项目管理'},
    '/engineering/dag-executions': {group: '数据工程', label: 'DAG 执行历史'},
    '/governance/collect-tasks': {group: '数据治理', label: '采集任务'},
    '/governance/collect-task-history': {group: '数据治理', label: '采集任务历史'},
    '/governance/metadata': {group: '数据治理', label: '元数据管理'},
    '/governance/data-standards': {group: '数据治理', label: '数据标准'},
};

/**
 * Friendly labels for the dynamic sub-segments we know about
 * (e.g. `/engineering/dags/:id/edit` → tail segment "edit" → "编辑").
 */
const subRouteLabels: Record<string, string> = {
    history: '历史记录',
    executions: '执行历史',
    edit: '编辑',
    new: '新建',
};

function labelForSegment(segment: string): string {
    return subRouteLabels[segment] ?? segment;
}

/** 纯数字段是 Snowflake id（如项目/DAG id），面包屑和标题里直接跳过，不展示原始 id */
function isRawIdSegment(segment: string): boolean {
    return /^\d+$/.test(segment);
}

/**
 * Resolve a pathname into an ordered list of breadcrumb segments.
 * Returns an empty array for the root path "/" (caller is expected
 * to render nothing in that case).
 */
export function resolveBreadcrumb(pathname: string): BreadcrumbSegment[] {
    if (!pathname || pathname === '/') {
        return [];
    }

    // Exact match
    const exact = breadcrumbMap[pathname];
    if (exact) {
        return exact.group
            ? [
                {label: exact.group, path: '/'},
                {label: exact.label, path: pathname},
            ]
            : [{label: exact.label, path: pathname}];
    }

    // Longest prefix match against the static map. This handles
    // dynamic routes like `/engineering/dags/:id/edit`,
    // `/engineering/dags/:id/executions`,
    // `/engineering/sync-jobs/:id/history`,
    // `/governance/collect-tasks/:taskId/history`.
    const segments = pathname.split('/').filter(Boolean);
    let matchLen = 0;
    let matchEntry: BreadcrumbEntry | null = null;
    for (let i = segments.length; i > 0; i--) {
        const candidate = '/' + segments.slice(0, i).join('/');
        const entry = breadcrumbMap[candidate];
        if (entry) {
            matchLen = i;
            matchEntry = entry;
            break;
        }
    }

    if (matchEntry) {
        const result: BreadcrumbSegment[] = matchEntry.group
            ? [{label: matchEntry.group, path: '/'}]
            : [];
        const parentPath = '/' + segments.slice(0, matchLen).join('/');
        result.push({label: matchEntry.label, path: parentPath});

        // Tail segments after the matched prefix (e.g. "history" → "历史记录").
        const tail = segments.slice(matchLen);
        tail.forEach((seg, idx) => {
            if (isRawIdSegment(seg)) return;
            const segPath =
                '/' + segments.slice(0, matchLen + idx + 1).join('/');
            result.push({label: labelForSegment(seg), path: segPath});
        });
        return result;
    }

    return [];
}

/**
 * Convenience for `document.title`: flatten segments to "group / label / ..."
 */
export function resolveBreadcrumbTitle(pathname: string): string {
    return resolveBreadcrumb(pathname)
        .map((s) => s.label)
        .join(' / ');
}

/**
 * 用于 `document.title`：只取最长静态前缀对应的 `group + label`，忽略动态 tail。
 * 这样 deep 页面（如编辑页）的 title 不会带上 "编辑" 等三级段。
 */
export function resolveMenuTitle(pathname: string): string {
    if (!pathname || pathname === '/') {
        return '';
    }

    // 优先精确匹配
    const exact = breadcrumbMap[pathname];
    if (exact) {
        return exact.group ? `${exact.group} / ${exact.label}` : exact.label;
    }

    // 最长静态前缀匹配
    const segments = pathname.split('/').filter(Boolean);
    for (let i = segments.length; i > 0; i--) {
        const candidate = '/' + segments.slice(0, i).join('/');
        const entry = breadcrumbMap[candidate];
        if (entry) {
            return entry.group ? `${entry.group} / ${entry.label}` : entry.label;
        }
    }

    return '';
}
