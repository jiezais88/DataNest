// 导出文件下载工具（xlsx/csv 通用）。历史背景：downloadBlob 曾在合规检查页和我的收藏页逐字符复制两份；
// 且 responseType:'blob' 时后端业务异常（HTTP 200 + Result JSON 信封）会被 axios 包成 Blob，
// 响应拦截器的 'code' in data 判断对 Blob 恒为 false → 错误 JSON 被当成导出文件存盘并假成功提示。
// 收敛到这里统一处理。
import {notify} from './notify';

/** 触发浏览器下载 Blob 文件 */
export function downloadBlob(blob: Blob, filename: string) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
}

/**
 * 下载导出文件 Blob（xlsx/csv 通用，带错误检出）。
 * 成功响应 content-type 为二进制（xlsx/csv）；业务异常为 application/json（Result 信封），
 * 检出后解析 message 弹错误提示，不触发下载。
 * @returns true = 已触发下载；false = 导出失败（已弹提示）
 */
export async function downloadExportBlob(blob: Blob, filename: string): Promise<boolean> {
    if (blob.type.includes('json')) {
        try {
            const err = JSON.parse(await blob.text()) as { message?: string };
            notify.error(err.message || '导出失败');
        } catch {
            notify.error('导出失败');
        }
        return false;
    }
    downloadBlob(blob, filename);
    return true;
}
