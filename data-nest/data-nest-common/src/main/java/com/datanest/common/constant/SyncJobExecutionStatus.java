package com.datanest.common.constant;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 批量同步任务执行状态枚举。
 */
public enum SyncJobExecutionStatus {

    PENDING("PENDING", "未执行"),
    RUNNING("RUNNING", "运行中"),
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败");

    private final String code;
    private final String label;

    SyncJobExecutionStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static SyncJobExecutionStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (SyncJobExecutionStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(SyncJobExecutionStatus::getCode).collect(Collectors.toList());
    }

    public boolean isPending() {
        return this == PENDING;
    }

    public boolean isRunning() {
        return this == RUNNING;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isFailed() {
        return this == FAILED;
    }
}
