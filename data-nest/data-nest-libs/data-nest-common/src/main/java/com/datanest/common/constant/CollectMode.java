package com.datanest.common.constant;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 元数据采集模式枚举。
 */
public enum CollectMode {

    FULL("FULL", "全量采集"),
    FULL_INCREMENT("FULL_INCREMENT", "全量 + 增量");

    private final String code;
    private final String label;

    CollectMode(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static CollectMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (CollectMode mode : values()) {
            if (mode.code.equalsIgnoreCase(code)) {
                return mode;
            }
        }
        return null;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(CollectMode::getCode).collect(Collectors.toList());
    }

    public static String regexPattern() {
        return codes().stream().collect(Collectors.joining("|"));
    }
}
