package com.datanest.common.constant;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据源连接状态枚举。
 */
public enum DataSourceStatus {

    NORMAL("NORMAL", "正常"),
    ERROR("ERROR", "异常"),
    OFFLINE("OFFLINE", "已下线"),
    UNKNOWN("UNKNOWN", "未检测");

    private final String code;
    private final String label;

    DataSourceStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static DataSourceStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (DataSourceStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(DataSourceStatus::getCode).collect(Collectors.toList());
    }

    public static String regexPattern() {
        return codes().stream().collect(Collectors.joining("|"));
    }
}
