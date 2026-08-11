package com.datanest.governance.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * XLSX 导出助手（导出统一规范，2026-08-11 起取代 CSV；conventions-backend §8）。
 * <p>
 * 基于 POI SXSSFWorkbook 流式写出（滚动窗口 500 行，大数据量不占内存）。
 * 统一处理两件事：
 * <ul>
 *   <li>列宽按内容估算（CJK 记 2 宽，封顶 {@link #MAX_COL_WIDTH} 字符）；
 *       不用 {@code autoSizeColumn}——headless 容器无字体度量会失效，且 SXSSF 滚动窗口下不可回溯</li>
 *   <li>时间单元格统一 {@code yyyy-MM-dd HH:mm:ss}（用户约定；禁止 ISO 带 T 格式出镜）</li>
 * </ul>
 * 字符串经 POI 显式写为 STRING 单元格，天然无公式注入面（无需 CSV 时代的前置单引号）。
 * 用法：数据全部查完再写流；只 write 不 close 响应流（由容器管理），workbook 用 try-with-resources。
 */
public final class XlsxExportHelper {

    /** 导出时间格式（2026-08-11 用户约定：呈现给用户的时间一律 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd） */
    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 列宽上限（字符数，CJK 记 2） */
    private static final int MAX_COL_WIDTH = 60;
    /** 列宽下限（字符数） */
    private static final int MIN_COL_WIDTH = 8;

    private XlsxExportHelper() {
    }

    /** 时间单元格：null → 空串；否则按 yyyy-MM-dd HH:mm:ss */
    public static String time(LocalDateTime value) {
        return value == null ? "" : TIME_FORMATTER.format(value);
    }

    /** 创建流式工作簿（滚动窗口 500 行） */
    public static SXSSFWorkbook workbook() {
        return new SXSSFWorkbook(500);
    }

    /**
     * 写一行并按内容累计列宽。null → 空字符串单元格；Number 写数值单元格；其余写字符串单元格。
     *
     * @param widths 每列当前最大显示宽度（就地更新；长度需 ≥ values.size()）
     */
    public static void writeRow(SXSSFSheet sheet, int rowIdx, List<Object> values, int[] widths) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            Cell cell = row.createCell(i);
            if (value instanceof Number n) {
                cell.setCellValue(n.doubleValue());
                if (widths != null) widths[i] = Math.max(widths[i], MIN_COL_WIDTH);
            } else {
                String text = value == null ? "" : String.valueOf(value);
                cell.setCellValue(text);
                if (widths != null) widths[i] = Math.max(widths[i], displayWidth(text));
            }
        }
    }

    /** 全部行写完后调用一次：按累计内容宽度设列宽（下限 MIN_COL_WIDTH，上限 MAX_COL_WIDTH） */
    public static void applyColumnWidths(SXSSFSheet sheet, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            int chars = Math.min(Math.max(widths[i], MIN_COL_WIDTH), MAX_COL_WIDTH);
            // POI 列宽单位 = 1/256 字符宽，CJK 场景再留一点余量
            sheet.setColumnWidth(i, (chars + 2) * 256);
        }
    }

    /** 写完响应流；dispose 清 SXSSF 临时文件（workbook 的 close 由调用方 try-with-resources 负责） */
    public static void write(SXSSFWorkbook wb, OutputStream out) throws IOException {
        wb.write(out);
        wb.dispose();
    }

    /** 显示宽度估算：CJK/全角字符记 2，其余记 1 */
    private static int displayWidth(String text) {
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            w += text.charAt(i) > 0xFF ? 2 : 1;
        }
        return w;
    }
}
