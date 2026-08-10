import {QUALITY_HEALTH_LABEL} from '@/types/quality';
import type {QualityHealthLevel} from '@/types/quality';

/**
 * 质量评分 + 健康度徽章（Sprint 6 NG8 单一出处）。
 * <p>
 * 评分列表页 / 元数据「质量」页签 / 血缘图谱节点共用的评分徽章：
 * 展示「评分值 + 健康度中文」，未配置规则（score 为 null/undefined）显示灰色「— / 暂无质量」。
 * 颜色对齐 PRD §6.5.1：优秀绿 / 良好绿（较浅）/ 一般黄 / 差红 / 暂无灰。
 */
interface QualityScoreBadgeProps {
    /** 0-100 分；null/undefined 表示未配置规则 */
    score?: number | string | null;
    healthLevel?: QualityHealthLevel | string | null;
    /** 紧凑模式（血缘节点小徽章用），默认 false */
    compact?: boolean;
    /** 表格模式（列表单元格用）：评分数字/徽章用小字号，与其它表格行高对齐 */
    table?: boolean;
}

const LEVEL_CLASS: Record<string, {text: string; badge: string}> = {
    EXCELLENT: {text: 'text-[#15803d]', badge: 'bg-[#dcfce7] text-[#15803d]'},
    GOOD: {text: 'text-[#16a34a]', badge: 'bg-[#dcfce7] text-[#16a34a]'},
    WARNING: {text: 'text-[#d97706]', badge: 'bg-[#fef3c7] text-[#d97706]'},
    BAD: {text: 'text-[#dc2626]', badge: 'bg-[#fee2e2] text-[#dc2626]'},
};

export default function QualityScoreBadge({score, healthLevel, compact = false, table = false}: QualityScoreBadgeProps) {
    const hasScore = score !== null && score !== undefined && score !== '';
    const level = healthLevel as QualityHealthLevel;
    const cls = hasScore ? LEVEL_CLASS[level] : null;

    if (compact) {
        return hasScore && cls ? (
            <span className={`inline-flex items-center rounded-full px-ds-2 py-0.5 text-[11px] font-semibold ${cls.badge}`}>
                {score}
                {QUALITY_HEALTH_LABEL[level] ?? ''}
            </span>
        ) : (
            <span className="inline-flex items-center rounded-full px-ds-2 py-0.5 text-[11px] font-medium bg-[#f1f5f9] text-[#94a3b8]">
                —
            </span>
        );
    }

    // 表格模式：评分数字/徽章用小字号（text-ds-small），保证单元格行高与其它列表页一致
    const scoreClass = table ? 'text-ds-small' : 'text-ds-subhead';
    const badgeClass = table ? 'text-[11px]' : 'text-ds-small';

    return (
        <div className="flex items-center gap-ds-2 whitespace-nowrap">
            {hasScore && cls ? (
                <>
                    <span className={`${scoreClass} font-bold ${cls.text}`}>{score}</span>
                    <span className={`inline-flex items-center rounded-full px-ds-2 py-0.5 ${badgeClass} font-semibold ${cls.badge}`}>
                        {QUALITY_HEALTH_LABEL[level] ?? level}
                    </span>
                </>
            ) : (
                <>
                    <span className={`${scoreClass} font-bold text-[#94a3b8]`}>—</span>
                    <span className={`inline-flex items-center rounded-full px-ds-2 py-0.5 ${badgeClass} font-medium bg-[#f1f5f9] text-[#94a3b8]`}>
                        暂无质量
                    </span>
                </>
            )}
        </div>
    );
}
