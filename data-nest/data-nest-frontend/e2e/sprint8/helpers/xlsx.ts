import * as XLSX from 'xlsx';

/**
 * xlsx 导出文件解析（E2E 断言用，2026-08-11 导出格式 CSV→xlsx 后引入 devDep `xlsx`）。
 */

/** 解析首个 sheet 为字符串行数组（raw:false 统一转字符串，空单元格补 ''） */
export function parseXlsxRows(buf: Buffer): string[][] {
    const wb = XLSX.read(buf, {type: 'buffer'});
    const sheet = wb.Sheets[wb.SheetNames[0]];
    return XLSX.utils.sheet_to_json<string[]>(sheet, {header: 1, raw: false, defval: ''});
}

/** 拍平为单文本（行内逗号连接，行间换行）——兼容原 CSV 时代的 toContain 断言写法 */
export function xlsxText(rows: string[][]): string {
    return rows.map(r => r.join(',')).join('\n');
}
