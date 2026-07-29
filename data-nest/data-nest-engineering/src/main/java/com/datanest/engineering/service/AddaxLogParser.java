package com.datanest.engineering.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AddaxLogParser {

    private static final Logger logger = LoggerFactory.getLogger(AddaxLogParser.class);

    /**
     * Addax 6.x 英文统计日志：
     * StandAloneJobContainerCommunicator - Total 2 records, 74 bytes | Speed 24B/s, 0 records/s | Error 0 records, 0 bytes
     */
    private static final Pattern TOTAL_RECORDS_PATTERN = Pattern.compile(
            "Total\\s+(\\d+)\\s+records", Pattern.CASE_INSENSITIVE);

    /**
     * Addax 6.x 英文任务结束统计块：
     * Number of rec             :                   2
     */
    private static final Pattern NUMBER_OF_RECORDS_PATTERN = Pattern.compile(
            "Number\\s+of\\s+rec\\s*[:：]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * 失败记录数：
     * Failed record             :                   0
     */
    private static final Pattern FAILED_RECORDS_PATTERN = Pattern.compile(
            "Failed\\s+record(?:s)?\\s*[:：]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * 中文 fork 或旧版 Addax 可能使用的关键词。
     */
    private static final Pattern READ_ROWS_PATTERN = Pattern.compile(
            "(?:读出记录总数|read record count|read records)\\s*[:：]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_ROWS_PATTERN = Pattern.compile(
            "(?:写出记录总数|write record count|write records)\\s*[:：]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_ROWS_PATTERN = Pattern.compile(
            "(?:读写失败总数|error record count|error records|error rows)\\s*[:：]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    public AddaxParseResult parse(List<String> logLines) {
        if (logLines == null || logLines.isEmpty()) {
            return new AddaxParseResult(0, 0, 0, Collections.emptyList(), Collections.emptyList());
        }

        String logContent = String.join("\n", logLines);

        long readRows = extractNumber(READ_ROWS_PATTERN, logContent);
        long writeRows = extractNumber(WRITE_ROWS_PATTERN, logContent);
        long errorRows = extractNumber(ERROR_ROWS_PATTERN, logContent);

        // Addax 6.x 英文输出优先
        long totalRows = extractNumber(TOTAL_RECORDS_PATTERN, logContent);
        long numberOfRec = extractNumber(NUMBER_OF_RECORDS_PATTERN, logContent);
        long failedRecords = extractNumber(FAILED_RECORDS_PATTERN, logContent);

        if (totalRows > 0) {
            readRows = totalRows;
        }
        if (numberOfRec > 0) {
            writeRows = numberOfRec;
        }
        if (failedRecords > 0) {
            errorRows = failedRecords;
        }

        // 兜底：如果写出为 0 但没有失败，且读出有值，则默认写出等于读出
        if (writeRows == 0 && readRows > 0 && errorRows == 0) {
            writeRows = readRows;
        }

        List<String> errorLines = new ArrayList<>();
        for (String line : logLines) {
            if (line.contains("ERROR") || line.contains("Exception") || line.contains("失败")) {
                errorLines.add(line.trim());
            }
        }

        logger.debug("Addax 日志解析结果: readRows={}, writeRows={}, errorRows={}", readRows, writeRows, errorRows);
        return new AddaxParseResult(readRows, writeRows, errorRows, errorLines, logLines);
    }

    private long extractNumber(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    public record AddaxParseResult(long readRows, long writeRows, long errorRows, List<String> errorLines,
                                   List<String> logLines) {
    }
}
