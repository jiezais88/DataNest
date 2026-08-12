package com.datanest.common.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * CSV 导出助手（Sprint 10 F1 SQL 终端导出走后端；conventions-backend §8 导出统一走后端）。
 * <p>
 * UTF-8 带 BOM（\uFEFF），Excel 打开中文不乱码；RFC4180 转义（含逗号/引号/换行的值用双引号包裹，内部引号双写）。
 * 默认 CRLF 行结束（Excel 兼容）。
 */
public final class CsvExportHelper {

    private CsvExportHelper() {
    }

    /**
     * 将列头 + 行数据写出为 CSV 到响应流。
     *
     * @param out        响应流（只写不关，由容器管理）
     * @param columns    列头（顺序即列序）
     * @param rows       行数据（Map 按列头取值，缺省 null）
     */
    public static void write(OutputStream out, List<String> columns, List<Map<String, Object>> rows)
            throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write('\uFEFF'); // UTF-8 BOM，Excel 中文不乱码
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(columns.get(i)));
        }
        sb.append("\r\n");
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sb.append(',');
                Object v = row.get(columns.get(i));
                sb.append(escape(v == null ? "" : String.valueOf(v)));
            }
            sb.append("\r\n");
        }
        writer.write(sb.toString());
        writer.flush();
    }

    /** RFC4180 转义：含逗号/引号/换行/回车时用双引号包裹，内部引号双写 */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
