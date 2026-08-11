// Sprint 8 F3：质量报告共用常量（判定级别中文标签等）。

/** 判定级别 → 中文标签（SEVERE/WARNING/PASS/UNAVAILABLE → 严重/警告/通过/不可用） */
export const LEVEL_LABEL: Record<string, string> = {
    SEVERE: '严重',
    WARNING: '警告',
    PASS: '通过',
    UNAVAILABLE: '不可用',
};

export function levelLabel(level?: string): string {
    return (level && LEVEL_LABEL[level]) || level || '—';
}
