package com.datanest.common.constant;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 元数据采集任务状态枚举。
 */
public enum CollectTaskStatus {

    NEVER_EXECUTED("NEVER_EXECUTED", "未执行"),
    RUNNING("RUNNING", "运行中"),
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    TERMINATED("TERMINATED", "已终止");

    private final String code;
    private final String label;

    CollectTaskStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static CollectTaskStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (CollectTaskStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(CollectTaskStatus::getCode).collect(Collectors.toList());
    }
}
