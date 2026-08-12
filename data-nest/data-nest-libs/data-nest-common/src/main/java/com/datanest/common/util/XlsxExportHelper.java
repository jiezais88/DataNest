package com.datanest.common.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
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
 * 2026-08-12 由 governance 下沉至 common：所有服务导出统一经此工具（用户拍板：系统所有导出走后端）。
 * 基于 POI SXSSFWorkbook 流式写出（滚动窗口 500 行，大数据量不占内存）。
 * 统一处理三件事：
 * <ul>
 *   <li>列宽按内容估算（CJK/全角记 2 宽，数字/小写/大写/空格分档加权，封顶 {@link #MAX_COL_WIDTH} 字符）；
 *       不用 {@code autoSizeColumn}——headless 容器无字体度量会失效，且 SXSSF 滚动窗口下不可回溯</li>
 *   <li>时间单元格统一 {@code yyyy-MM-dd HH:mm:ss}（用户约定；禁止 ISO 带 T 格式出镜）</li>
 *   <li>表头行 {@link #writeHeaderRow} 加粗 + 浅灰底（参与列宽累计），数据行 {@link #writeRow} 无样式</li>
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
     * 写表头行：加粗 + 浅灰底（视觉区分），并按内容累计列宽。
     *
     * @param headers 表头文本（null 按空串）
     * @param widths  每列当前最大显示宽度（就地更新；长度需 ≥ headers.size()）
     */
    public static void writeHeaderRow(SXSSFSheet sheet, int rowIdx, List<String> headers, int[] widths) {
        Row row = sheet.createRow(rowIdx);
        CellStyle headerStyle = headerStyle(sheet);
        for (int i = 0; i < headers.size(); i++) {
            String text = headers.get(i) == null ? "" : headers.get(i);
            Cell cell = row.createCell(i);
            cell.setCellValue(text);
            cell.setCellStyle(headerStyle);
            if (widths != null) widths[i] = Math.max(widths[i], displayWidth(text));
        }
    }

    /**
     * 写一行并按内容累计列宽。null → 空字符串单元格；Number 写数值单元格（按数值宽度估算，避免大数字显示 ####）；
     * 其余写字符串单元格。
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
                // 大数字（如 999999999999）不能只给 MIN_COL_WIDTH，否则 Excel 显示 ####；按数字串宽估算
                if (widths != null) widths[i] = Math.max(widths[i], displayWidth(n.toString()));
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

    private static CellStyle headerStyle(SXSSFSheet sheet) {
        CellStyle style = sheet.getWorkbook().createCellStyle();
        Font font = sheet.getWorkbook().createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /**
     * 显示宽度估算（2026-08-12 用户拍板：全系统 xlsx 自动适配列宽）。
     * 分档加权：CJK/全角/emoji（codepoint > 0x2E7F，含增补平面 surrogate 只算一次）记 2；
     * 空格/小数点记 0.5；数字记 0.6；小写记 0.75；大写记 0.85；其它记 1。ceil 取整。
     */
    private static int displayWidth(String text) {
        double w = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint > 0x2E7F) {
                w += 2.0;
            } else if (codePoint == ' ' || codePoint == '.') {
                w += 0.5;
            } else if (codePoint >= '0' && codePoint <= '9') {
                w += 0.6;
            } else if (codePoint >= 'a' && codePoint <= 'z') {
                w += 0.75;
            } else if (codePoint >= 'A' && codePoint <= 'Z') {
                w += 0.85;
            } else {
                w += 1.0;
            }
        }
        return (int) Math.ceil(w);
    }
}
