package com.datanest.common.constant;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 任务触发方式枚举（适用于同步任务与采集任务）。
 */
public enum TaskTriggerType {

    MANUAL("MANUAL", "手动触发"),
    CRON("CRON", "Cron 定时"),
    DAG("DAG", "DAG 编排");

    private final String code;
    private final String label;

    TaskTriggerType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static TaskTriggerType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (TaskTriggerType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(TaskTriggerType::getCode).collect(Collectors.toList());
    }

    public static String regexPattern() {
        return codes().stream().collect(Collectors.joining("|"));
    }

    public boolean isCron() {
        return this == CRON;
    }

    public boolean isManual() {
        return this == MANUAL;
    }
}
