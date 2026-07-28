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

    private static final Pattern READ_ROWS_PATTERN = Pattern.compile(
            "(?:读出记录总数|read record count|read records)[\\s\\S]*?[:：]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_ROWS_PATTERN = Pattern.compile(
            "(?:写出记录总数|write record count|write records)[\\s\\S]*?[:：]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_ROWS_PATTERN = Pattern.compile(
            "(?:读写失败总数|error record count|error records|error rows)[\\s\\S]*?[:：]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    public AddaxParseResult parse(List<String> logLines) {
        if (logLines == null || logLines.isEmpty()) {
            return new AddaxParseResult(0, 0, 0, Collections.emptyList(), Collections.emptyList());
        }

        String logContent = String.join("\n", logLines);
        long readRows = extractNumber(READ_ROWS_PATTERN, logContent);
        long writeRows = extractNumber(WRITE_ROWS_PATTERN, logContent);
        long errorRows = extractNumber(ERROR_ROWS_PATTERN, logContent);
        if (writeRows == 0 && readRows > 0 && errorRows == 0) {
            writeRows = readRows;
        }

        List<String> errorLines = new ArrayList<>();
        for (String line : logLines) {
            if (line.contains("ERROR") || line.contains("Exception") || line.contains("失败")) {
                errorLines.add(line.trim());
            }
        }

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
