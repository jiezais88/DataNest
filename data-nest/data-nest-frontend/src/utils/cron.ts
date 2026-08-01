import cronstrue from 'cronstrue';
import 'cronstrue/locales/zh_CN';
import parseExpression from 'cron-parser';

/**
 * cron 表达式工具的唯一出处（cronstrue 中文描述 + cron-parser 下次执行时间）。
 * 历史背景：CronPicker、DAG Editor、collect-tasks 各写了一份 cronstrue/parseExpression
 * 调用。收敛到这里，其它文件不要直接 import cronstrue / cron-parser。
 */

/** cron 表达式是否合法 */
export function isValidCron(cron?: string): boolean {
    if (!cron) return false;
    try {
        parseExpression.parse(cron);
        return true;
    } catch {
        return false;
    }
}

/** cron → 中文描述（如「每 2 分钟」）；解析失败返回 fallback（默认返回原文） */
export function describeCron(cron?: string, fallback?: string): string {
    if (!cron) return '';
    try {
        return cronstrue.toString(cron, {locale: 'zh_CN'});
    } catch {
        return fallback ?? cron;
    }
}

/** 下一次执行时间；表达式非法返回 null */
export function nextRunTime(cron?: string): Date | null {
    if (!cron) return null;
    try {
        return parseExpression.parse(cron).next().toDate();
    } catch {
        return null;
    }
}

/** 接下来 count 次执行时间；表达式非法返回空数组 */
export function nextRunTimes(cron: string, count = 5): Date[] {
    try {
        const interval = parseExpression.parse(cron);
        const runs: Date[] = [];
        for (let i = 0; i < count; i++) {
            runs.push(interval.next().toDate());
        }
        return runs;
    } catch {
        return [];
    }
}
