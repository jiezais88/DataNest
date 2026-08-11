package com.datanest.governance.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * CSV 导出助手（导出统一规范，2026-08-11 起；conventions-backend §8）。
 * <p>
 * 基于 Commons CSV 的 CSVPrinter：单元格转义/引号规则由框架负责，不再手写 esc()。
 * 统一处理两件事：
 * <ul>
 *   <li>UTF-8 BOM（Excel 打开中文不乱码）</li>
 *   <li>公式注入防护：String 单元格首字符为 {@code = + - @} 时前置单引号
 *       （Number 等非字符串类型不受影响，原样输出）</li>
 * </ul>
 * 用法：数据全部查完再写流；只 flush 不 close（响应流由容器管理）。
 */
public final class CsvExportHelper {

    /** 公式注入危险首字符（Excel/LibreOffice 会把这些开头的单元格当公式/命令执行） */
    private static final String FORMULA_PREFIX_CHARS = "=+-@";

    private CsvExportHelper() {
    }

    /** 创建带 UTF-8 BOM 的 CSVPrinter（记录分隔符 \n，与既有导出文件一致）。 */
    public static CSVPrinter printer(OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write("\uFEFF");
        return new CSVPrinter(writer,
                CSVFormat.Builder.create(CSVFormat.DEFAULT).setRecordSeparator("\n").build());
    }

    /**
     * 单元格值安全化：null → 空串；String 首字符命中公式注入字符时前置单引号；
     * 非字符串（Number 等）原样返回（CSVPrinter 按 toString 输出，无注入面）。
     */
    public static Object safe(Object value) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String s)) {
            return value;
        }
        if (!s.isEmpty() && FORMULA_PREFIX_CHARS.indexOf(s.charAt(0)) >= 0) {
            return "'" + s;
        }
        return s;
    }
}
