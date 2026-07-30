package com.datanest.common.constant;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 批量同步任务同步模式枚举。
 */
public enum SyncMode {

    FULL("FULL", "全量同步"),
    INCREMENTAL("INCREMENTAL", "增量同步");

    private final String code;
    private final String label;

    SyncMode(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static SyncMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (SyncMode mode : values()) {
            if (mode.code.equalsIgnoreCase(code)) {
                return mode;
            }
        }
        return null;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(SyncMode::getCode).collect(Collectors.toList());
    }

    public static String regexPattern() {
        return codes().stream().collect(Collectors.joining("|"));
    }

    public boolean isIncremental() {
        return this == INCREMENTAL;
    }
}
